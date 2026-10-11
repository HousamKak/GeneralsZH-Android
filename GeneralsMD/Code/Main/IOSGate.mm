// GeneralsX @feature ZH Commander 09/10/2026 iOS counterpart of the Android launcher's
// ActivationActivity / DataDownloadActivity / Support button.
//
// ZHIOSRunGate() runs on the main thread before the engine initializes. It shows its own UIKit
// window and keeps the run loop turning (the app stays responsive) until:
//   1. the device holds a valid license -- a one-time key redeemed with the license server,
//      which answers with a license signed by a key only it holds; verified here against the
//      server's public key, and bound to this device (identifierForVendor), as on Android;
//   2. the game data is in Documents (the engine's working directory on iOS) -- downloaded from
//      the site with the license, resumed over HTTP Range, every file checked against the
//      manifest's SHA-256, an update offered when a newer data version is published.
// Then the window goes away and the engine starts. ZHIOSInstallHooks() gives the game's own
// menus the app version, update offers and the support report (ZHCommander.h).
//
// Updates of the app itself come through AltStore/SideStore (the site's /altstore.json), so
// there is no in-app updater here.
//
// GeneralsX @feature ZH Commander 11/10/2026 Brought up to the Android app:
//   - the game-data screens are a loading screen (dark, the site's artwork crossfading, a big
//     percentage, the segmented bar, speed and time left, rotating tips), as DataDownloadActivity;
//   - online with nothing to set up, as ZeroSetup: the iOS engine simulates at 60 Hz (the PC
//     client's rate), so the cross-play marker and the GeneralsOnline community data are kept in
//     step with "Play with PC players" (on unless turned off), and the data is fetched before the
//     game when it is missing or stale;
//   - GeneralsOnline sign-in from the game (a server-issued code, the browser, CheckLogin), the
//     session marker the engine reads (GeneralsOnline_AndroidGlue.cpp), and its renewal at start;
//   - an ONLINE screen (account, cross-play switch, the GeneralsOnline data) for the game's
//     openCrossPlay and ADVANCED;
//   - the support report's "Online" section.
// The checks no longer run inside a main-queue block: a run loop turned from inside one never
// drains the main queue (CFRunLoop will not re-enter it), so anything they dispatched to the main
// queue, the progress of an automatically resumed download among them, waited until they ended.

#import <UIKit/UIKit.h>
#import <QuartzCore/QuartzCore.h>
#import <Security/Security.h>
#import <CommonCrypto/CommonDigest.h>
#import <CoreText/CoreText.h>

#include <zlib.h>
#include <strings.h>
#include <unistd.h>
#include <cstdio>
#include <algorithm>
#include <functional>
#include <string>
#include <vector>

#include "Common/ZHCommander.h"

static NSString *const kActivateURL = @"https://gzh-license.housam-kak20.workers.dev/v1/activate";
static NSString *const kSite = @"https://zerohour.housamkak.com";
// SubjectPublicKeyInfo (DER, base64) of the license server's signing key -- LicenseGate.PUBLIC_KEY_B64.
static NSString *const kLicensePublicKey =
	@"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEAhE861NJAUCPcAoeya8LDyNmUux2QKucHdX3ndZ+IjYbkPYVqQU/GXBmn5tsuKg1Wp3b70Xx6aLSbsvJEfjOmg==";
static NSString *const kLicenseKey = @"zh.license";
static NSString *const kStateFile = @".zh-data.json";
// Optional packs (mods): the last manifest seen, and the ids the player turned on.
static NSString *const kManifestDefaultsKey = @"zh.manifest";
static NSString *const kPacksDefaultsKey = @"zh.enabledPacks";

// GeneralsOnline (the online service PC players use) and its community data, as on Android
// (GeneralsOnlineSession.java, DataPackInstaller.java, ZeroSetup.java).
static NSString *const kGoApiBase = @"https://api.playgenerals.online/env/prod/contract/1/";
static NSString *const kGoApiBaseAlt = @"https://api-ru.playgenerals.online/env/prod/contract/1/";
static NSString *const kGoLoginURLFormat = @"https://www.playgenerals.online/login/?gamecode=%@";
static NSString *const kGameReplaysSignUpURL = @"https://www.gamereplays.org/";
// The iOS engine is built with SAGE_HIGH_FPS_SIM (CMakePresets.json, ios-vulkan), so it announces
// itself as the 60 Hz client (NextGenMP_defines.h); a session must be asked for under the same id.
static NSString *const kGoClientId = @"gen_online_60hz";
static NSString *const kGoPatchManifestURL = @"https://cdn.playgenerals.online/manifest.json";
static NSString *const kGoPatchFile = @"GeneralsOnlineGameData/500_900_CommunityPatch_CoreINI.big";
static NSString *const kGoPatchVersionKey = @"zh.goDataVersion";
static NSString *const kGoPatchNoExeKey = @"zh.goDataNoPcExe";
static NSString *const kGoInstallIdKey = @"zh.goInstallId";
static NSString *const kCrossPlayOffKey = @"zh.crossplayOff";
// Read by the engine: in the game folder, it reports the PC client's EXE checksum
// (GlobalData::init); in the user-data folder, it keeps the community data unmounted
// (ArchiveFileSystem::loadMods).
static NSString *const kCrossPlayMarker = @"gx_pc_compat.txt";
static NSString *const kPatchDisableMarker = @"gx_no_community_patch.txt";
static NSString *const kPcExeName = @"GeneralsOnlineZH_60.exe";
static NSString *const kPcExeSeedFile = @"pc_exe_crc_seed.txt";

static NSSet *enabledPacks(void)
{
	NSArray *ids = [NSUserDefaults.standardUserDefaults arrayForKey:kPacksDefaultsKey];
	return [NSSet setWithArray:ids ?: @[]];
}

static NSArray *publishedPacks(void)
{
	NSData *data = [NSUserDefaults.standardUserDefaults dataForKey:kManifestDefaultsKey];
	NSDictionary *json = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
	NSArray *packs = [json isKindOfClass:NSDictionary.class] ? json[@"packs"] : nil;
	return [packs isKindOfClass:NSArray.class] ? packs : @[];
}

// ---------------------------------------------------------------- helpers

static NSString *hexOf(const unsigned char *bytes, size_t len)
{
	NSMutableString *hex = [NSMutableString stringWithCapacity:len * 2];
	for (size_t i = 0; i < len; i++) {
		[hex appendFormat:@"%02x", bytes[i]];
	}
	return hex;
}

static NSString *sha256OfString(NSString *s)
{
	NSData *d = [s dataUsingEncoding:NSUTF8StringEncoding];
	unsigned char digest[CC_SHA256_DIGEST_LENGTH];
	CC_SHA256(d.bytes, (CC_LONG)d.length, digest);
	return hexOf(digest, sizeof(digest));
}

static NSString *sha256OfFile(NSString *path)
{
	NSInputStream *in = [NSInputStream inputStreamWithFileAtPath:path];
	if (in == nil) {
		return nil;
	}
	[in open];
	CC_SHA256_CTX ctx;
	CC_SHA256_Init(&ctx);
	uint8_t *buf = (uint8_t *)malloc(1 << 20);
	NSInteger n;
	while ((n = [in read:buf maxLength:(1 << 20)]) > 0) {
		CC_SHA256_Update(&ctx, buf, (CC_LONG)n);
	}
	free(buf);
	[in close];
	unsigned char digest[CC_SHA256_DIGEST_LENGTH];
	CC_SHA256_Final(digest, &ctx);
	return hexOf(digest, sizeof(digest));
}

// The site's fonts (SIL OFL), shipped in <app>/UIFonts by package-ios-zh.sh: loaded straight
// from the file, so nothing needs registering in Info.plist. Falls back when a file is missing.
static UIFont *zhFont(NSString *file, CGFloat size, UIFont *fallback)
{
	static NSMutableDictionary<NSString *, id> *cache;
	if (cache == nil) {
		cache = [NSMutableDictionary dictionary];
	}
	CGFontRef graphicsFont = (__bridge CGFontRef)cache[file];
	if (graphicsFont == NULL) {
		NSString *path = [[NSBundle.mainBundle.resourcePath stringByAppendingPathComponent:@"UIFonts"]
			stringByAppendingPathComponent:file];
		NSData *data = [NSData dataWithContentsOfFile:path];
		if (data == nil) {
			return fallback;
		}
		CGDataProviderRef provider = CGDataProviderCreateWithCFData((__bridge CFDataRef)data);
		graphicsFont = CGFontCreateWithDataProvider(provider);
		CGDataProviderRelease(provider);
		if (graphicsFont == NULL) {
			return fallback;
		}
		cache[file] = (__bridge_transfer id)graphicsFont;
	}
	CTFontRef font = CTFontCreateWithGraphicsFont(graphicsFont, size, NULL, NULL);
	return font != NULL ? (__bridge_transfer UIFont *)font : fallback;
}

// Whether an AltStore source entry is newer than this app. By build number when the entry has one
// (CFBundleVersion, which only goes up): version names restarted at 0.1.N, so "0.1.9" is newer than
// "1.5.8". Older entries without a build number are compared by name.
static BOOL isNewerRelease(NSDictionary *entry)
{
	if (![entry isKindOfClass:NSDictionary.class]) {
		return NO;
	}
	NSDictionary *info = NSBundle.mainBundle.infoDictionary;
	id build = entry[@"buildVersion"];
	if ([build isKindOfClass:NSString.class] && [info[@"CFBundleVersion"] isKindOfClass:NSString.class]) {
		return [(NSString *)build longLongValue] > [info[@"CFBundleVersion"] longLongValue];
	}
	NSString *latest = entry[@"version"];
	NSString *mine = info[@"CFBundleShortVersionString"];
	return [latest isKindOfClass:NSString.class] && mine != nil
		&& [mine compare:latest options:NSNumericSearch] == NSOrderedAscending;
}

static NSString *documentsDir(void)
{
	return NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
}

// Library/Application Support/GeneralsX: the engine's registry.ini lives here, and its user data
// (Options.ini, saves, replays, Maps/) one level down, in GeneralsZH (GlobalData.cpp's Apple
// branch). The GeneralsOnline session and the PC checksum seed sit beside them
// (GeneralsOnline_AndroidGlue.cpp, GXRemoteConfig::updateDirPath).
static NSString *appSupportDir(void)
{
	NSString *base = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, YES).firstObject;
	return [base stringByAppendingPathComponent:@"GeneralsX"];
}

static NSString *engineUserDataDir(void)
{
	return [appSupportDir() stringByAppendingPathComponent:@"GeneralsZH"];
}

static NSString *updateDir(void)
{
	return [appSupportDir() stringByAppendingPathComponent:@"update"];
}

static NSString *sessionMarkerPath(void)
{
	return [appSupportDir() stringByAppendingPathComponent:@"generalsonline_session.txt"];
}

// Turns the main run loop until done() says so, so the screen stays live and animated.
static void spinUntil(BOOL (^done)(void))
{
	while (!done()) {
		[NSRunLoop.currentRunLoop runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
	}
}

// Runs work on a background queue while the main run loop keeps turning, and returns its result:
// for hashing and unpacking hundreds of megabytes without freezing the screen.
static id runInBackground(id (^work)(void))
{
	__block id result = nil;
	dispatch_semaphore_t finished = dispatch_semaphore_create(0);
	dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
		result = work();
		dispatch_semaphore_signal(finished);
	});
	spinUntil(^BOOL {
		return dispatch_semaphore_wait(finished, DISPATCH_TIME_NOW) == 0;
	});
	return result;
}

// A request answered on a background thread (not the main one: it blocks).
static NSData *syncRequest(NSURLRequest *request, NSInteger *status)
{
	__block NSData *result = nil;
	__block NSInteger code = 0;
	dispatch_semaphore_t finished = dispatch_semaphore_create(0);
	[[NSURLSession.sharedSession dataTaskWithRequest:request
		completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
			result = error == nil ? data : nil;
			code = [response isKindOfClass:NSHTTPURLResponse.class] ? ((NSHTTPURLResponse *)response).statusCode : 0;
			dispatch_semaphore_signal(finished);
		}] resume];
	dispatch_semaphore_wait(finished, DISPATCH_TIME_FOREVER);
	if (status != NULL) {
		*status = code;
	}
	return result;
}

static void writeSmallFile(NSString *path, NSString *text)
{
	[NSFileManager.defaultManager createDirectoryAtPath:path.stringByDeletingLastPathComponent
		withIntermediateDirectories:YES attributes:nil error:nil];
	[text writeToFile:path atomically:YES encoding:NSUTF8StringEncoding error:nil];
}

// identifierForVendor survives reinstalls while another app of the same vendor stays installed;
// the first value seen is kept in the defaults so the license keeps matching.
static NSString *deviceHash(void)
{
	NSUserDefaults *defaults = NSUserDefaults.standardUserDefaults;
	NSString *idv = [defaults stringForKey:@"zh.device"];
	if (idv.length == 0) {
		idv = UIDevice.currentDevice.identifierForVendor.UUIDString ?: NSUUID.UUID.UUIDString;
		[defaults setObject:idv forKey:@"zh.device"];
	}
	return sha256OfString([@"gzh-license:" stringByAppendingString:idv]);
}

static BOOL licenseValid(NSString *license, NSString *device)
{
	NSRange dot = [license rangeOfString:@"."];
	if (dot.location == NSNotFound || dot.location == 0) {
		return NO;
	}
	NSData *payload = [[NSData alloc] initWithBase64EncodedString:[license substringToIndex:dot.location] options:0];
	NSData *signature = [[NSData alloc] initWithBase64EncodedString:[license substringFromIndex:dot.location + 1] options:0];
	NSData *spki = [[NSData alloc] initWithBase64EncodedString:kLicensePublicKey options:0];
	if (payload == nil || signature == nil || spki.length < 65) {
		return NO;
	}
	// SecKey wants the raw X9.63 point (04 || X || Y): the last 65 bytes of the P-256 SPKI.
	NSData *point = [spki subdataWithRange:NSMakeRange(spki.length - 65, 65)];
	NSDictionary *attrs = @{
		(id)kSecAttrKeyType: (id)kSecAttrKeyTypeECSECPrimeRandom,
		(id)kSecAttrKeyClass: (id)kSecAttrKeyClassPublic,
		(id)kSecAttrKeySizeInBits: @256,
	};
	SecKeyRef key = SecKeyCreateWithData((__bridge CFDataRef)point, (__bridge CFDictionaryRef)attrs, NULL);
	if (key == NULL) {
		return NO;
	}
	// The license carries a DER (X9.62) signature, which is what this algorithm verifies.
	BOOL ok = SecKeyVerifySignature(key, kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
		(__bridge CFDataRef)payload, (__bridge CFDataRef)signature, NULL);
	CFRelease(key);
	if (!ok) {
		return NO;
	}
	NSDictionary *claims = [NSJSONSerialization JSONObjectWithData:payload options:0 error:nil];
	return [claims isKindOfClass:NSDictionary.class] && [claims[@"v"] integerValue] == 1
		&& [claims[@"device"] isEqual:device];
}

static NSString *storedLicense(void)
{
	NSString *license = [NSUserDefaults.standardUserDefaults stringForKey:kLicenseKey];
	return license != nil && licenseValid(license, deviceHash()) ? license : nil;
}

// Present while a download has started and not completed; lists the files already finished
// for that data version, so an interruption loses at most the unfinished part of one file.
static NSString *const kProgressFile = @".zh-data-progress.json";

static NSString *progressPath(void)
{
	return [documentsDir() stringByAppendingPathComponent:kProgressFile];
}

static BOOL downloadIncomplete(void)
{
	return [NSFileManager.defaultManager fileExistsAtPath:progressPath()];
}

static NSDictionary<NSString *, NSString *> *finishedFiles(NSString *version)
{
	NSData *d = [NSData dataWithContentsOfFile:progressPath()];
	NSDictionary *json = d ? [NSJSONSerialization JSONObjectWithData:d options:0 error:nil] : nil;
	if (![json isKindOfClass:NSDictionary.class] || ![json[@"version"] isEqual:version]
		|| ![json[@"files"] isKindOfClass:NSDictionary.class]) {
		return @{};
	}
	return json[@"files"];
}

static void writeProgress(NSString *version, NSDictionary *finished)
{
	NSData *json = [NSJSONSerialization dataWithJSONObject:@{@"version": version ?: @"", @"files": finished}
		options:0 error:nil];
	[json writeToFile:progressPath() atomically:YES];
}

// Half a download is not game data, even with INIZH.big already in the folder.
static BOOL haveGameData(void)
{
	NSFileManager *fm = NSFileManager.defaultManager;
	NSString *docs = documentsDir();
	return !downloadIncomplete()
		&& ([fm fileExistsAtPath:[docs stringByAppendingPathComponent:@"INIZH.big"]]
			|| [fm fileExistsAtPath:[docs stringByAppendingPathComponent:@"INI.big"]]);
}

// Blocking from the caller's point of view, but the main run loop keeps turning, so the UI stays
// live. Only used while the gate's own window is up.
static NSData *runRequest(NSURLRequest *request, NSInteger *status)
{
	__block NSData *result = nil;
	__block NSInteger code = 0;
	__block BOOL done = NO;
	[[NSURLSession.sharedSession dataTaskWithRequest:request
		completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
			result = error == nil ? data : nil;
			code = [response isKindOfClass:NSHTTPURLResponse.class] ? ((NSHTTPURLResponse *)response).statusCode : 0;
			done = YES;
		}] resume];
	while (!done) {
		[NSRunLoop.currentRunLoop runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
	}
	*status = code;
	return result;
}

// ---------------------------------------------------------------- game data

@interface ZHDataFile : NSObject
@property (nonatomic, copy) NSString *path;
@property (nonatomic, copy) NSString *sha256;
@property (nonatomic, copy) NSString *key;
@property (nonatomic) long long size;
@end
@implementation ZHDataFile
@end

static NSDictionary *readState(void)
{
	NSData *d = [NSData dataWithContentsOfFile:[documentsDir() stringByAppendingPathComponent:kStateFile]];
	id json = d ? [NSJSONSerialization JSONObjectWithData:d options:0 error:nil] : nil;
	return [json isKindOfClass:NSDictionary.class] ? json : nil;
}

static NSDictionary<NSString *, NSString *> *installedFiles(void)
{
	NSMutableDictionary *out = [NSMutableDictionary dictionary];
	for (NSDictionary *f in readState()[@"files"]) {
		if ([f isKindOfClass:NSDictionary.class] && f[@"path"] && f[@"sha256"]) {
			out[f[@"path"]] = f[@"sha256"];
		}
	}
	return out;
}

// Streams one file into <target>.part, resuming what is already there.
// GeneralsX @bugfix ZH Commander 11/10/2026 The waiting side polls received/done (atomic) instead
// of being told through the main queue, which a run loop turned inside a main-queue block never
// drains: the progress of a download started from such a block stood still until it ended.
@interface ZHDownloader : NSObject <NSURLSessionDataDelegate>
@property (nonatomic, strong) NSFileHandle *handle;
@property (atomic) long long received;
@property (atomic) BOOL done;
@property (atomic) BOOL failed;
@end

@implementation ZHDownloader
- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)task
	didReceiveResponse:(NSURLResponse *)response completionHandler:(void (^)(NSURLSessionResponseDisposition))completion
{
	NSInteger status = ((NSHTTPURLResponse *)response).statusCode;
	if (status == 200) {
		[self.handle truncateFileAtOffset:0];  // the whole file is coming: start the part over
		self.received = 0;
	} else if (status != 206) {
		self.failed = YES;
		completion(NSURLSessionResponseCancel);
		return;
	}
	completion(NSURLSessionResponseAllow);
}
- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)task didReceiveData:(NSData *)data
{
	[self.handle writeData:data];
	self.received = self.received + (long long)data.length;
}
- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error
{
	if (error != nil) {
		self.failed = YES;
	}
	self.done = YES;
}
@end

// ---------------------------------------------------------------- GeneralsOnline community data

// GeneralsX @feature ZH Commander 11/10/2026 The data every GeneralsOnline PC client mounts, so this
// device's INI checksum is the PC one (DataPackInstaller.java). Published as a zip beside a small
// JSON manifest; only GeneralsOnlineGameData/ and Maps/ are unpacked, into the engine's user-data
// folder, where ArchiveFileSystem::loadMods mounts the patch. The PC executable in the same zip is
// not written anywhere: it is hashed, the first half of generateExeCRC()'s PC branch, and the state
// left in pc_exe_crc_seed.txt for the engine (GlobalData.cpp, pcExeCrcFromDataPack).

namespace
{
	struct ZipEntry
	{
		std::string name;
		uint16_t method = 0;
		uint64_t compSize = 0;
		uint64_t size = 0;
		uint64_t localOffset = 0;
	};

	uint16_t rd16(const unsigned char *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
	uint32_t rd32(const unsigned char *p) { return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24); }
	uint64_t rd64(const unsigned char *p) { return (uint64_t)rd32(p) | ((uint64_t)rd32(p + 4) << 32); }

	// The central directory, ZIP64 included (the end record, its locator and the extended
	// information field).
	bool zipReadDirectory(FILE *f, std::vector<ZipEntry> &out)
	{
		if (fseeko(f, 0, SEEK_END) != 0) {
			return false;
		}
		const off_t fileSize = ftello(f);
		if (fileSize < 22) {
			return false;
		}
		const off_t tail = fileSize < 65557 ? fileSize : 65557;
		std::vector<unsigned char> buf((size_t)tail);
		if (fseeko(f, fileSize - tail, SEEK_SET) != 0 || fread(buf.data(), 1, buf.size(), f) != buf.size()) {
			return false;
		}
		long eocd = -1;
		for (long i = (long)tail - 22; i >= 0; --i) {
			if (rd32(&buf[(size_t)i]) == 0x06054b50) {
				eocd = i;
				break;
			}
		}
		if (eocd < 0) {
			return false;
		}
		uint64_t count = rd16(&buf[(size_t)eocd + 10]);
		uint64_t cdSize = rd32(&buf[(size_t)eocd + 12]);
		uint64_t cdOffset = rd32(&buf[(size_t)eocd + 16]);
		if ((count == 0xFFFF || cdSize == 0xFFFFFFFFu || cdOffset == 0xFFFFFFFFu) && eocd >= 20
			&& rd32(&buf[(size_t)eocd - 20]) == 0x07064b50) {
			const uint64_t zip64End = rd64(&buf[(size_t)eocd - 20 + 8]);
			unsigned char rec[56];
			if (fseeko(f, (off_t)zip64End, SEEK_SET) != 0 || fread(rec, 1, sizeof(rec), f) != sizeof(rec)
				|| rd32(rec) != 0x06064b50) {
				return false;
			}
			count = rd64(rec + 32);
			cdSize = rd64(rec + 40);
			cdOffset = rd64(rec + 48);
		}
		if (cdSize > (256ull << 20)) {
			return false;
		}
		std::vector<unsigned char> cd((size_t)cdSize);
		if (fseeko(f, (off_t)cdOffset, SEEK_SET) != 0 || fread(cd.data(), 1, cd.size(), f) != cd.size()) {
			return false;
		}
		size_t pos = 0;
		for (uint64_t n = 0; n < count; ++n) {
			if (pos + 46 > cd.size() || rd32(&cd[pos]) != 0x02014b50) {
				return false;
			}
			ZipEntry e;
			e.method = rd16(&cd[pos + 10]);
			e.compSize = rd32(&cd[pos + 20]);
			e.size = rd32(&cd[pos + 24]);
			const size_t nameLen = rd16(&cd[pos + 28]);
			const size_t extraLen = rd16(&cd[pos + 30]);
			const size_t commentLen = rd16(&cd[pos + 32]);
			e.localOffset = rd32(&cd[pos + 42]);
			if (pos + 46 + nameLen + extraLen + commentLen > cd.size()) {
				return false;
			}
			e.name.assign((const char *)&cd[pos + 46], nameLen);
			size_t x = pos + 46 + nameLen;
			const size_t xEnd = x + extraLen;
			while (x + 4 <= xEnd) {
				const uint16_t id = rd16(&cd[x]);
				const size_t len = rd16(&cd[x + 2]);
				size_t p = x + 4;
				const size_t pEnd = p + len;
				if (pEnd > xEnd) {
					break;
				}
				if (id == 0x0001) {
					// The 64-bit values of the fields that are all ones, in this order.
					if (e.size == 0xFFFFFFFFu && p + 8 <= pEnd) { e.size = rd64(&cd[p]); p += 8; }
					if (e.compSize == 0xFFFFFFFFu && p + 8 <= pEnd) { e.compSize = rd64(&cd[p]); p += 8; }
					if (e.localOffset == 0xFFFFFFFFu && p + 8 <= pEnd) { e.localOffset = rd64(&cd[p]); p += 8; }
				}
				x = pEnd;
			}
			std::replace(e.name.begin(), e.name.end(), '\\', '/');
			out.push_back(e);
			pos += 46 + nameLen + extraLen + commentLen;
		}
		return true;
	}

	// Streams one entry's bytes, uncompressed, through sink. Stored and deflated entries.
	bool zipReadEntry(FILE *f, const ZipEntry &e, const std::function<bool(const unsigned char *, size_t)> &sink)
	{
		unsigned char local[30];
		if (fseeko(f, (off_t)e.localOffset, SEEK_SET) != 0 || fread(local, 1, sizeof(local), f) != sizeof(local)
			|| rd32(local) != 0x04034b50) {
			return false;
		}
		if (fseeko(f, (off_t)(e.localOffset + 30 + rd16(local + 26) + rd16(local + 28)), SEEK_SET) != 0) {
			return false;
		}
		std::vector<unsigned char> in(1 << 16);
		std::vector<unsigned char> out(1 << 16);
		uint64_t left = e.compSize;
		if (e.method == 0) {
			while (left > 0) {
				const size_t n = fread(in.data(), 1, (size_t)std::min<uint64_t>(left, in.size()), f);
				if (n == 0 || !sink(in.data(), n)) {
					return false;
				}
				left -= n;
			}
			return true;
		}
		if (e.method != 8) {
			return false;
		}
		z_stream z = {};
		if (inflateInit2(&z, -MAX_WBITS) != Z_OK) {
			return false;
		}
		int rc = Z_OK;
		while (rc != Z_STREAM_END) {
			if (z.avail_in == 0) {
				if (left == 0) {
					break;
				}
				const size_t n = fread(in.data(), 1, (size_t)std::min<uint64_t>(left, in.size()), f);
				if (n == 0) {
					break;
				}
				left -= n;
				z.next_in = in.data();
				z.avail_in = (uInt)n;
			}
			z.next_out = out.data();
			z.avail_out = (uInt)out.size();
			rc = inflate(&z, Z_NO_FLUSH);
			if (rc != Z_OK && rc != Z_STREAM_END) {
				break;
			}
			const size_t produced = out.size() - z.avail_out;
			if (produced > 0 && !sink(out.data(), produced)) {
				rc = Z_DATA_ERROR;
				break;
			}
		}
		inflateEnd(&z);
		return rc == Z_STREAM_END;
	}

	// generateExeCRC()'s PC branch over the executable (rotate left by one, add the byte; the first
	// 1001 blocks of 64 KiB), then the 1.4 version number; and whether the executable carries the
	// GeneralsOnline logic-CRC revision marker. DataPackInstaller.pcExeCrcState, byte for byte.
	struct PcExeCrc
	{
		uint32_t crc = 0;
		uint64_t fed = 0;
		size_t matched = 0;
		bool marker = false;

		void feed(const unsigned char *p, size_t n)
		{
			static const char kMarker[] = "MARKER:OfficialLogicCRCRevision";
			const size_t markerLen = sizeof(kMarker) - 1;
			const uint64_t limit = 1001ull * 65536ull;
			for (size_t i = 0; i < n; ++i) {
				if (fed < limit) {
					crc = ((crc << 1) | (crc >> 31)) + p[i];
					++fed;
				}
				if (!marker) {
					// The marker has no repeated prefix, so restarting a broken match is exact.
					if (p[i] == (unsigned char)kMarker[matched]) {
						if (++matched == markerLen) {
							marker = true;
						}
					} else {
						matched = p[i] == (unsigned char)kMarker[0] ? 1 : 0;
					}
				}
			}
		}

		uint32_t finish() const
		{
			uint32_t c = crc;
			const uint32_t version = (1u << 16) | 4u;
			for (int i = 0; i < 4; ++i) {
				c = ((c << 1) | (c >> 31)) + ((version >> (8 * i)) & 0xFF);
			}
			return c;
		}
	};
}

// What a patch install is doing, read by whoever shows it (any thread).
@interface ZHPatchProgress : NSObject
@property (atomic) long long done;
@property (atomic) long long total;
@property (atomic) int phase;  // 0 checking, 1 downloading, 2 installing
@end
@implementation ZHPatchProgress
@end

static NSString *patchFilePath(void)
{
	return [engineUserDataDir() stringByAppendingPathComponent:kGoPatchFile];
}

// The installed version, or nil: a recorded version with the file gone is no install.
static NSString *patchInstalledVersion(void)
{
	NSString *version = [NSUserDefaults.standardUserDefaults stringForKey:kGoPatchVersionKey];
	return version.length > 0 && [NSFileManager.defaultManager fileExistsAtPath:patchFilePath()] ? version : nil;
}

static BOOL patchEnabled(void)
{
	return ![NSFileManager.defaultManager fileExistsAtPath:
		[engineUserDataDir() stringByAppendingPathComponent:kPatchDisableMarker]];
}

static NSString *pcExeSeedPath(void)
{
	return [updateDir() stringByAppendingPathComponent:kPcExeSeedFile];
}

// A seed with its revision line (written since 03/10/2026 on Android, always here).
static BOOL hasPcExeCrcSeed(void)
{
	NSString *seed = [NSString stringWithContentsOfFile:pcExeSeedPath() encoding:NSUTF8StringEncoding error:nil];
	return [seed containsString:@"crc_revision="];
}

static NSDictionary *fetchPatchManifest(NSTimeInterval timeout)
{
	NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:kGoPatchManifestURL]];
	req.timeoutInterval = timeout;
	req.cachePolicy = NSURLRequestReloadIgnoringLocalCacheData;
	[req setValue:@"application/json" forHTTPHeaderField:@"Accept"];
	NSInteger status = 0;
	NSData *data = syncRequest(req, &status);
	id json = data != nil && status == 200 ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
	return [json isKindOfClass:NSDictionary.class] ? json : nil;
}

static BOOL isWantedPatchEntry(const std::string &name)
{
	static const char *const prefixes[] = { "GeneralsOnlineGameData/", "Maps/" };
	for (const char *prefix : prefixes) {
		const size_t len = strlen(prefix);
		if (name.size() >= len && strncasecmp(name.c_str(), prefix, len) == 0) {
			return true;
		}
	}
	return false;
}

// Downloads, checks and unpacks the current package. Blocking: call it off the main thread.
// Returns nil on success, or what went wrong.
static NSString *installCommunityPatch(ZHPatchProgress *progress)
{
	progress.phase = 0;
	NSDictionary *manifest = fetchPatchManifest(20);
	if (manifest == nil) {
		return @"Could not reach GeneralsOnline.";
	}
	NSString *version = [manifest[@"version"] description] ?: @"";
	NSString *downloadUrl = [manifest[@"download_url"] isKindOfClass:NSString.class] ? manifest[@"download_url"] : @"";
	// Trimmed: one manifest published its digest with a leading space.
	NSString *expectedSha = [[[manifest[@"sha256"] description] ?: @""
		stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet] lowercaseString];
	if (![downloadUrl hasPrefix:@"https://"]) {
		return @"The GeneralsOnline manifest has no usable download.";
	}

	NSFileManager *fm = NSFileManager.defaultManager;
	NSString *zip = [NSTemporaryDirectory() stringByAppendingPathComponent:@"generalsonline-datapack.zip"];
	[fm removeItemAtPath:zip error:nil];
	[fm createFileAtPath:zip contents:nil attributes:nil];
	ZHDownloader *dl = [ZHDownloader new];
	dl.handle = [NSFileHandle fileHandleForWritingAtPath:zip];
	progress.total = [manifest[@"size"] longLongValue];
	progress.phase = 1;
	NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:downloadUrl]];
	req.timeoutInterval = 30;
	NSURLSession *session = [NSURLSession sessionWithConfiguration:NSURLSessionConfiguration.defaultSessionConfiguration
		delegate:dl delegateQueue:nil];
	[[session dataTaskWithRequest:req] resume];
	while (!dl.done) {
		progress.done = dl.received;
		usleep(50000);
	}
	[session finishTasksAndInvalidate];
	[dl.handle closeFile];
	progress.done = dl.received;
	if (dl.failed) {
		[fm removeItemAtPath:zip error:nil];
		return @"The GeneralsOnline data download stopped.";
	}
	progress.phase = 2;
	if (expectedSha.length > 0 && ![sha256OfFile(zip) isEqualToString:expectedSha]) {
		[fm removeItemAtPath:zip error:nil];
		return @"The GeneralsOnline data did not match its checksum.";
	}

	NSString *root = [engineUserDataDir() stringByStandardizingPath];
	[fm createDirectoryAtPath:root withIntermediateDirectories:YES attributes:nil error:nil];
	NSString *rootPrefix = [root stringByAppendingString:@"/"];
	FILE *f = fopen(zip.fileSystemRepresentation, "rb");
	if (f == NULL) {
		return @"Could not open the GeneralsOnline data.";
	}
	std::vector<ZipEntry> entries;
	NSString *failure = nil;
	PcExeCrc pcExe;
	bool sawPcExe = false;
	int written = 0;
	if (!zipReadDirectory(f, entries)) {
		failure = @"The GeneralsOnline data is not a readable zip.";
	}
	for (size_t i = 0; failure == nil && i < entries.size(); ++i) {
		const ZipEntry &e = entries[i];
		if (strcasecmp(e.name.c_str(), kPcExeName.UTF8String) == 0) {
			sawPcExe = zipReadEntry(f, e, [&pcExe](const unsigned char *p, size_t n) {
				pcExe.feed(p, n);
				return true;
			});
			continue;
		}
		if (!isWantedPatchEntry(e.name)) {
			continue;
		}
		NSString *name = [NSString stringWithUTF8String:e.name.c_str()];
		// A zip is an untrusted list of paths: nothing may land outside the user-data folder.
		NSString *target = [[root stringByAppendingPathComponent:name] stringByStandardizingPath];
		if (name == nil || [name hasPrefix:@"/"] || [name containsString:@".."] || ![target hasPrefix:rootPrefix]) {
			failure = @"The GeneralsOnline data has an entry outside its folder.";
			break;
		}
		if ([name hasSuffix:@"/"]) {
			[fm createDirectoryAtPath:target withIntermediateDirectories:YES attributes:nil error:nil];
			continue;
		}
		[fm createDirectoryAtPath:target.stringByDeletingLastPathComponent withIntermediateDirectories:YES attributes:nil error:nil];
		// Beside the target, then moved over it: a running game keeps reading the file it opened.
		NSString *part = [target stringByAppendingString:@".part"];
		FILE *out = fopen(part.fileSystemRepresentation, "wb");
		bool ok = out != NULL && zipReadEntry(f, e, [out](const unsigned char *p, size_t n) {
			return fwrite(p, 1, n, out) == n;
		});
		if (out != NULL && fclose(out) != 0) {
			ok = false;
		}
		if (!ok || rename(part.fileSystemRepresentation, target.fileSystemRepresentation) != 0) {
			[fm removeItemAtPath:part error:nil];
			failure = @"Could not unpack the GeneralsOnline data. Free up some space and try again.";
			break;
		}
		++written;
	}
	fclose(f);
	[fm removeItemAtPath:zip error:nil];
	if (failure == nil && written == 0) {
		failure = @"The GeneralsOnline data held none of the expected files.";
	}
	if (failure != nil) {
		return failure;
	}

	NSUserDefaults *defaults = NSUserDefaults.standardUserDefaults;
	if (sawPcExe) {
		writeSmallFile(pcExeSeedPath(), [NSString stringWithFormat:@"%u\n%@\ncrc_revision=%d\n",
			(unsigned)pcExe.finish(), version, pcExe.marker ? 1 : 0]);
		[defaults removeObjectForKey:kGoPatchNoExeKey];
	} else {
		// No PC executable in this package: the engine falls back to the number it knows.
		[fm removeItemAtPath:pcExeSeedPath() error:nil];
		[defaults setObject:version forKey:kGoPatchNoExeKey];
	}
	[defaults setObject:version forKey:kGoPatchVersionKey];
	fprintf(stderr, "INFO: GeneralsOnline data %s installed (%d files), PC checksum seed %s\n",
		version.UTF8String, written, sawPcExe ? "written" : "not in the package");
	return nil;
}

// ---------------------------------------------------------------- cross-play (ZeroSetup)

// The iOS engine runs at 60 Hz, the PC client's rate, so the only choice left is the switch:
// "Play with PC players" on (the default) or off.
static BOOL crossPlayWanted(void)
{
	return ![NSUserDefaults.standardUserDefaults boolForKey:kCrossPlayOffKey];
}

// Cheap and synchronous: at every start and whenever the switch changes. The marker claims the PC
// client's EXE checksum, the disable marker keeps the community data unmounted; with the switch
// off the device is retail and plays with phones that are retail too.
static void ensureCrossPlay(void)
{
	NSFileManager *fm = NSFileManager.defaultManager;
	const BOOL wanted = crossPlayWanted();
	NSString *marker = [documentsDir() stringByAppendingPathComponent:kCrossPlayMarker];
	if (wanted && ![fm fileExistsAtPath:marker]) {
		[fm createFileAtPath:marker contents:[NSData data] attributes:nil];
	} else if (!wanted && [fm fileExistsAtPath:marker]) {
		[fm removeItemAtPath:marker error:nil];
	}
	NSString *disable = [engineUserDataDir() stringByAppendingPathComponent:kPatchDisableMarker];
	if (wanted && [fm fileExistsAtPath:disable]) {
		[fm removeItemAtPath:disable error:nil];
	} else if (!wanted && ![fm fileExistsAtPath:disable]) {
		writeSmallFile(disable, @"The community data patch is disabled while this file exists.\n");
	}
}

static void setCrossPlay(BOOL on)
{
	[NSUserDefaults.standardUserDefaults setBool:!on forKey:kCrossPlayOffKey];
	ensureCrossPlay();
}

// ---------------------------------------------------------------- GeneralsOnline session

// One stable id per installation, and the two fields derived from it that stand in for a MAC
// address and a volume serial (NetworkDiagnostics.java): structurally identifiers of this install,
// never taken from hardware. The engine sends the same values, read from the session marker.
static NSString *goInstallId(void)
{
	NSUserDefaults *defaults = NSUserDefaults.standardUserDefaults;
	NSString *installId = [defaults stringForKey:kGoInstallIdKey];
	if (installId.length == 0) {
		installId = NSUUID.UUID.UUIDString.lowercaseString;
		[defaults setObject:installId forKey:kGoInstallIdKey];
	}
	return installId;
}

static NSData *sha256Bytes(NSString *s)
{
	NSData *d = [s dataUsingEncoding:NSUTF8StringEncoding];
	NSMutableData *digest = [NSMutableData dataWithLength:CC_SHA256_DIGEST_LENGTH];
	CC_SHA256(d.bytes, (CC_LONG)d.length, (unsigned char *)digest.mutableBytes);
	return digest;
}

static NSString *goSyntheticMac(void)
{
	const unsigned char *b = (const unsigned char *)sha256Bytes([goInstallId() stringByAppendingString:@"|mac"]).bytes;
	return [NSString stringWithFormat:@"%02X:%02X:%02X:%02X:%02X:%02X", (b[0] & 0xFE) | 0x02, b[1], b[2], b[3], b[4], b[5]];
}

static NSString *goSyntheticVolumeSerial(void)
{
	const unsigned char *b = (const unsigned char *)sha256Bytes([goInstallId() stringByAppendingString:@"|vol"]).bytes;
	return [NSString stringWithFormat:@"%02X%02X%02X%02X", b[0], b[1], b[2], b[3]];
}

static NSDictionary<NSString *, NSString *> *readSessionMarker(void)
{
	NSString *text = [NSString stringWithContentsOfFile:sessionMarkerPath() encoding:NSUTF8StringEncoding error:nil];
	NSMutableDictionary *out = [NSMutableDictionary dictionary];
	for (NSString *line in [text componentsSeparatedByCharactersInSet:NSCharacterSet.newlineCharacterSet]) {
		NSRange eq = [line rangeOfString:@"="];
		if (eq.location != NSNotFound && eq.location > 0) {
			out[[line substringToIndex:eq.location]] = [line substringFromIndex:eq.location + 1];
		}
	}
	return out;
}

// The display name this device is signed in as, or nil.
static NSString *goSignedInName(void)
{
	NSDictionary *session = readSessionMarker();
	NSString *name = session[@"display_name"];
	return [session[@"session_token"] length] > 0 && name.length > 0 ? name : nil;
}

// One "key=value" per line, what GeneralsOnline_AndroidGlue.cpp reads (and rewrites on renewal).
static void writeSessionMarker(NSDictionary *auth)
{
	NSMutableString *text = [NSMutableString string];
	[text appendFormat:@"session_token=%@\n", [auth[@"session_token"] description] ?: @""];
	[text appendFormat:@"user_id=%@\n", [auth[@"user_id"] description] ?: @"-1"];
	[text appendFormat:@"display_name=%@\n", [auth[@"display_name"] description] ?: @""];
	[text appendFormat:@"ws_uri=%@\n", [auth[@"ws_uri"] description] ?: @""];
	[text appendFormat:@"machine_guid=%@\n", goInstallId()];
	[text appendFormat:@"mac_addr=%@\n", goSyntheticMac()];
	[text appendFormat:@"vol_serial=%@\n", goSyntheticVolumeSerial()];
	NSString *refresh = [auth[@"refresh_token"] description];
	if (refresh.length > 0) {
		[text appendFormat:@"refresh_token=%@\n", refresh];
	}
	writeSmallFile(sessionMarkerPath(), text);
}

// POSTs to the auth API, the alternate host when the first gives no answer at all (as the PC
// client's Network_UseAlternativeEndpoint). The body decides, the status annotates: an unclaimed
// login code answers 403 with a normal body. Background thread only. httpStatus may be NULL.
static NSDictionary *goPost(NSString *endpoint, NSDictionary *body, NSString *bearer, NSInteger *httpStatus)
{
	for (NSString *base in @[kGoApiBase, kGoApiBaseAlt]) {
		NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:[base stringByAppendingString:endpoint]]];
		req.HTTPMethod = @"POST";
		req.timeoutInterval = 10;
		[req setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
		if (bearer.length > 0) {
			[req setValue:[@"Bearer " stringByAppendingString:bearer] forHTTPHeaderField:@"Authorization"];
		}
		req.HTTPBody = [NSJSONSerialization dataWithJSONObject:body options:0 error:nil];
		NSInteger status = 0;
		NSData *data = syncRequest(req, &status);
		id json = data.length > 0 ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
		if ([json isKindOfClass:NSDictionary.class]) {
			if (httpStatus != NULL) {
				*httpStatus = status;
			}
			return json;
		}
	}
	return nil;
}

static NSDictionary *goIdentityBody(void)
{
	return @{@"machine_guid": goInstallId(), @"mac_addr": goSyntheticMac(), @"vol_serial": goSyntheticVolumeSerial(),
		@"exe_crc": @0, @"ini_crc": @0};
}

// Trades the newest refresh token for a fresh session before the engine starts (session tokens
// last fifteen minutes). Refresh tokens are single use and the engine rotates them during play,
// so this runs only while the engine is not up, and the marker's token is the one used.
static void refreshSessionBeforeGame(void)
{
	NSString *refresh = readSessionMarker()[@"refresh_token"];
	if (refresh.length == 0) {
		return;
	}
	NSDictionary *result = runInBackground(^id {
		return goPost(@"LoginWithToken", goIdentityBody(), refresh, NULL);
	});
	if ([result[@"result"] integerValue] == 1 && [result[@"session_token"] length] > 0) {
		writeSessionMarker(result);
		fprintf(stderr, "INFO: GeneralsOnline session renewed before the game\n");
	} else {
		fprintf(stderr, "INFO: GeneralsOnline session not renewed (offline or refused); the game renews it when needed\n");
	}
}

// ---------------------------------------------------------------- the loading screen

static UIColor *rgba(int r, int g, int b, int a)
{
	return [UIColor colorWithRed:r / 255.0 green:g / 255.0 blue:b / 255.0 alpha:a / 255.0];
}

// A loading bar in the game's colours (DataDownloadActivity.GameBar): a dark track with a thin
// frame, the filled part in segments from deep orange to amber, and a shine sweeping across it.
// Indeterminate, a lit block travels along the track instead.
@interface ZHGameBar : UIView
@property (nonatomic) CGFloat fraction;
@property (nonatomic) BOOL indeterminate;
@end

@implementation ZHGameBar {
	CADisplayLink *_link;
	CGFloat _phase;
}

- (instancetype)initWithFrame:(CGRect)frame
{
	if ((self = [super initWithFrame:frame])) {
		self.opaque = NO;
		self.backgroundColor = UIColor.clearColor;
		self.contentMode = UIViewContentModeRedraw;
	}
	return self;
}

- (void)didMoveToWindow
{
	[super didMoveToWindow];
	[_link invalidate];
	_link = nil;
	if (self.window != nil) {
		_link = [CADisplayLink displayLinkWithTarget:self selector:@selector(tick:)];
		_link.preferredFramesPerSecond = 30;
		[_link addToRunLoop:NSRunLoop.mainRunLoop forMode:NSRunLoopCommonModes];
	}
}

- (void)tick:(CADisplayLink *)link
{
	_phase += (CGFloat)(link.duration / 1.6);
	if (_phase > 1) {
		_phase -= 1;
	}
	[self setNeedsDisplay];
}

- (void)setFraction:(CGFloat)fraction
{
	_fraction = MAX(0, MIN(1, fraction));
	[self setNeedsDisplay];
}

- (void)drawRect:(CGRect)dirty
{
	CGContextRef ctx = UIGraphicsGetCurrentContext();
	const CGFloat w = self.bounds.size.width, h = self.bounds.size.height, radius = h / 4;
	UIBezierPath *track = [UIBezierPath bezierPathWithRoundedRect:self.bounds cornerRadius:radius];
	[rgba(0, 0, 0, 150) setFill];
	[track fill];

	CGFloat left = 0, right = w * _fraction;
	if (_indeterminate) {
		const CGFloat block = w * 0.28;
		left = (w + block) * _phase - block;
		right = left + block;
	}
	left = MAX(0, left);
	right = MIN(w, right);
	if (right > left) {
		CGContextSaveGState(ctx);
		[[UIBezierPath bezierPathWithRoundedRect:CGRectMake(left, 0, right - left, h) cornerRadius:radius] addClip];
		CGColorSpaceRef space = CGColorSpaceCreateDeviceRGB();
		NSArray *colors = @[(id)rgba(170, 52, 18, 255).CGColor, (id)rgba(255, 176, 72, 255).CGColor];
		CGGradientRef gradient = CGGradientCreateWithColors(space, (__bridge CFArrayRef)colors, NULL);
		CGContextDrawLinearGradient(ctx, gradient, CGPointMake(0, 0), CGPointMake(w, 0),
			kCGGradientDrawsBeforeStartLocation | kCGGradientDrawsAfterEndLocation);
		CGGradientRelease(gradient);
		// Segments, like an armour bar.
		const CGFloat segment = w / 32, gap = MAX(2, segment * 0.14);
		[rgba(10, 8, 4, 140) setFill];
		for (CGFloat x = segment; x < right; x += segment) {
			if (x > left) {
				UIRectFill(CGRectMake(x - gap / 2, 0, gap, h));
			}
		}
		// The shine, sweeping across the filled part.
		if (!_indeterminate) {
			const CGFloat band = MAX(h * 2.5, (right - left) * 0.18);
			const CGFloat cx = left + (right - left + band) * _phase - band;
			NSArray *shine = @[(id)rgba(255, 255, 255, 0).CGColor, (id)rgba(255, 255, 255, 110).CGColor,
				(id)rgba(255, 255, 255, 0).CGColor];
			CGGradientRef sweep = CGGradientCreateWithColors(space, (__bridge CFArrayRef)shine, NULL);
			CGContextDrawLinearGradient(ctx, sweep, CGPointMake(cx, 0), CGPointMake(cx + band, 0), 0);
			CGGradientRelease(sweep);
		}
		CGColorSpaceRelease(space);
		CGContextRestoreGState(ctx);
	}
	UIBezierPath *frame = [UIBezierPath bezierPathWithRoundedRect:CGRectInset(self.bounds, 0.6, 0.6) cornerRadius:radius];
	frame.lineWidth = 1.2;
	[rgba(255, 196, 120, 140) setStroke];
	[frame stroke];
}

@end

// The game-data screen as a loading screen (DataDownloadActivity): it opens plain and dark, then
// fetches the site's own artwork (the hero poster and the four screenshots, cached after the first
// time) and fades it in behind everything, one picture after another with a slow zoom. In front:
// the title, a big percentage, the bar, "812 / 2134 MB · 6.2 MB/s · 3 min left" and a tip that
// changes every few seconds. Offline it stays plain and dark: the pictures are decoration.
@interface ZHLoadingView : UIView
@property (nonatomic, readonly) UILabel *subtitle;
@property (nonatomic, readonly) UILabel *body;
@property (nonatomic, readonly) UILabel *status;
@property (nonatomic, readonly) UIButton *primary;
@property (nonatomic, readonly) UIButton *secondary;
- (void)setSubtitleText:(NSString *)text;
- (void)showBody:(NSString *)body primary:(NSString *)primary secondary:(NSString *)secondary;
- (void)startProgress;
- (void)setIndeterminate:(BOOL)indeterminate;
- (void)showDone:(long long)done total:(long long)total;
@end

static NSArray<NSString *> *loadingArt(void)
{
	return @[@"/hero/hero_poster.jpg", @"/shots/zh_base_wide.jpg", @"/shots/zh_army_moves.jpg",
		@"/shots/zh_missile_strike.jpg", @"/shots/zh_palace_hit.jpg"];
}

static NSArray<NSString *> *loadingTips(void)
{
	return @[
		@"Tip: tap DESELECT above the minimap to clear your selection, or to cancel an aimed power.",
		@"Tip: tap the minimap to look there; press and hold it to send your selected units.",
		@"Tip: double tap a unit to select every unit of that type on screen.",
		@"Tip: GROUPS opens ten control groups. Hold a number to save your selection, tap it to call it back.",
		@"Tip: ALERT jumps the camera to the last thing that happened, like your base under attack.",
		@"Tip: plug in a mouse and the game plays just like on a PC.",
		@"Tip: ZH COMMANDER, Zoom out lets you see more of the battlefield in skirmish and campaign.",
		@"Tip: online you play with PC players and phones alike, with nothing to set up.",
		@"Tip: a dozer that is building keeps building when you tap a unit right beside it.",
	];
}

@implementation ZHLoadingView {
	UIImageView *_backgrounds[2];
	int _front;
	int _nextArt;
	UILabel *_percent;
	ZHGameBar *_bar;
	UILabel *_tip;
	NSTimer *_artTimer;
	NSTimer *_tipTimer;
	int _tipIndex;
	long long _lastDone;
	CFTimeInterval _lastSample;
	double _bytesPerSecond;
}

static NSString *artCacheDir(void)
{
	NSString *caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES).firstObject;
	return [caches stringByAppendingPathComponent:@"zh-art"];
}

- (UILabel *)label:(UIStackView *)stack size:(CGFloat)size color:(UIColor *)color bold:(BOOL)bold
{
	UILabel *label = [UILabel new];
	label.numberOfLines = 0;
	label.textColor = color;
	label.font = bold ? [UIFont boldSystemFontOfSize:size] : [UIFont systemFontOfSize:size];
	label.layer.shadowColor = UIColor.blackColor.CGColor;
	label.layer.shadowOffset = CGSizeMake(0, 1);
	label.layer.shadowRadius = 4;
	label.layer.shadowOpacity = 0.8;
	[stack addArrangedSubview:label];
	return label;
}

- (UIButton *)button:(BOOL)primary
{
	UIButton *button = [UIButton buttonWithType:UIButtonTypeSystem];
	button.backgroundColor = primary ? rgba(194, 70, 27, 255) : rgba(255, 255, 255, 36);
	[button setTitleColor:UIColor.whiteColor forState:UIControlStateNormal];
	button.titleLabel.font = zhFont(@"zh_display.ttf", primary ? 22 : 19, [UIFont boldSystemFontOfSize:18]);
	button.layer.cornerRadius = 4;
	button.layer.borderWidth = primary ? 0 : 1;
	button.layer.borderColor = rgba(255, 196, 120, 140).CGColor;
	return button;
}

- (instancetype)initWithFrame:(CGRect)frame
{
	if (!(self = [super initWithFrame:frame])) {
		return nil;
	}
	self.backgroundColor = rgba(11, 12, 10, 255);
	self.clipsToBounds = YES;
	for (int i = 0; i < 2; ++i) {
		UIImageView *image = [UIImageView new];
		image.contentMode = UIViewContentModeScaleAspectFill;
		image.alpha = 0;
		image.translatesAutoresizingMaskIntoConstraints = NO;
		[self addSubview:image];
		[NSLayoutConstraint activateConstraints:@[
			[image.leadingAnchor constraintEqualToAnchor:self.leadingAnchor],
			[image.trailingAnchor constraintEqualToAnchor:self.trailingAnchor],
			[image.topAnchor constraintEqualToAnchor:self.topAnchor],
			[image.bottomAnchor constraintEqualToAnchor:self.bottomAnchor],
		]];
		_backgrounds[i] = image;
	}
	// Dark at the bottom, where the text is, lighter at the top so the picture shows.
	UIView *scrim = [UIView new];
	scrim.translatesAutoresizingMaskIntoConstraints = NO;
	scrim.userInteractionEnabled = NO;
	[self addSubview:scrim];
	[NSLayoutConstraint activateConstraints:@[
		[scrim.leadingAnchor constraintEqualToAnchor:self.leadingAnchor],
		[scrim.trailingAnchor constraintEqualToAnchor:self.trailingAnchor],
		[scrim.topAnchor constraintEqualToAnchor:self.topAnchor],
		[scrim.bottomAnchor constraintEqualToAnchor:self.bottomAnchor],
	]];
	CAGradientLayer *gradient = [CAGradientLayer layer];
	gradient.colors = @[(id)rgba(6, 7, 5, 70).CGColor, (id)rgba(6, 7, 5, 150).CGColor, (id)rgba(6, 7, 5, 235).CGColor];
	gradient.frame = UIScreen.mainScreen.bounds;
	gradient.needsDisplayOnBoundsChange = YES;
	[scrim.layer addSublayer:gradient];
	scrim.tag = 77;

	UIStackView *stack = [UIStackView new];
	stack.axis = UILayoutConstraintAxisVertical;
	stack.spacing = 8;
	stack.translatesAutoresizingMaskIntoConstraints = NO;
	[self addSubview:stack];
	UILayoutGuide *safe = self.safeAreaLayoutGuide;
	[NSLayoutConstraint activateConstraints:@[
		[stack.leadingAnchor constraintEqualToAnchor:safe.leadingAnchor constant:28],
		[stack.trailingAnchor constraintLessThanOrEqualToAnchor:safe.trailingAnchor constant:-28],
		[stack.widthAnchor constraintLessThanOrEqualToConstant:720],
		[stack.bottomAnchor constraintEqualToAnchor:safe.bottomAnchor constant:-20],
		[stack.topAnchor constraintGreaterThanOrEqualToAnchor:safe.topAnchor constant:12],
	]];
	NSLayoutConstraint *wide = [stack.widthAnchor constraintEqualToAnchor:safe.widthAnchor constant:-56];
	wide.priority = UILayoutPriorityDefaultHigh;
	wide.active = YES;

	UILabel *title = [self label:stack size:30 color:UIColor.whiteColor bold:YES];
	title.font = zhFont(@"zh_display.ttf", 38, [UIFont boldSystemFontOfSize:30]);
	title.attributedText = [[NSAttributedString alloc] initWithString:@"ZH COMMANDER"
		attributes:@{NSKernAttributeName: @3.5, NSFontAttributeName: title.font}];
	_subtitle = [self label:stack size:13 color:rgba(255, 176, 92, 255) bold:YES];
	_body = [self label:stack size:14 color:rgba(255, 255, 255, 220) bold:NO];
	_percent = [self label:stack size:46 color:UIColor.whiteColor bold:YES];
	_percent.hidden = YES;
	_bar = [ZHGameBar new];
	_bar.hidden = YES;
	[_bar.heightAnchor constraintEqualToConstant:20].active = YES;
	[stack addArrangedSubview:_bar];
	_status = [self label:stack size:13 color:rgba(255, 255, 255, 210) bold:NO];
	_tip = [self label:stack size:13 color:rgba(255, 197, 107, 255) bold:NO];
	_tip.font = [UIFont italicSystemFontOfSize:13];
	_primary = [self button:YES];
	_secondary = [self button:NO];
	[_primary.heightAnchor constraintEqualToConstant:50].active = YES;
	[_secondary.heightAnchor constraintEqualToConstant:44].active = YES;
	[stack addArrangedSubview:_primary];
	[stack addArrangedSubview:_secondary];
	[stack setCustomSpacing:14 afterView:_tip];
	return self;
}

- (void)layoutSubviews
{
	[super layoutSubviews];
	for (CALayer *layer in [self viewWithTag:77].layer.sublayers) {
		layer.frame = self.bounds;
	}
}

- (void)setSubtitleText:(NSString *)text
{
	_subtitle.attributedText = [[NSAttributedString alloc] initWithString:text.uppercaseString
		attributes:@{NSKernAttributeName: @2.6, NSFontAttributeName: _subtitle.font}];
}

- (void)didMoveToWindow
{
	[super didMoveToWindow];
	[_artTimer invalidate];
	[_tipTimer invalidate];
	_artTimer = nil;
	_tipTimer = nil;
	[NSObject cancelPreviousPerformRequestsWithTarget:self];
	if (self.window == nil) {
		return;
	}
	[self fetchArt];
	[self nextArt];
	// Timers on the main run loop fire in the gate's nested turns too (they are not main-queue blocks).
	_artTimer = [NSTimer timerWithTimeInterval:9 target:self selector:@selector(nextArt) userInfo:nil repeats:YES];
	[NSRunLoop.mainRunLoop addTimer:_artTimer forMode:NSRunLoopCommonModes];
}

// Downloads the pictures not cached yet, in the background; nextArt picks up whatever is there.
- (void)fetchArt
{
	NSString *dir = artCacheDir();
	[NSFileManager.defaultManager createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:nil];
	for (NSString *path in loadingArt()) {
		NSString *local = [dir stringByAppendingPathComponent:path.lastPathComponent];
		if ([NSFileManager.defaultManager fileExistsAtPath:local]) {
			continue;
		}
		NSURL *url = [NSURL URLWithString:[kSite stringByAppendingString:path]];
		[[NSURLSession.sharedSession dataTaskWithURL:url completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
			NSInteger status = [response isKindOfClass:NSHTTPURLResponse.class] ? ((NSHTTPURLResponse *)response).statusCode : 0;
			if (data.length > 0 && status == 200 && [UIImage imageWithData:data] != nil) {
				[data writeToFile:local atomically:YES];
			}
		}] resume];
	}
}

- (void)nextArt
{
	NSArray *art = loadingArt();
	UIImage *image = nil;
	for (NSUInteger tries = 0; tries < art.count && image == nil; ++tries) {
		NSString *name = [art[(NSUInteger)_nextArt % art.count] lastPathComponent];
		_nextArt = (_nextArt + 1) % (int)art.count;
		image = [UIImage imageWithContentsOfFile:[artCacheDir() stringByAppendingPathComponent:name]];
	}
	if (image == nil) {
		// Nothing cached yet: look again soon instead of waiting a whole turn.
		[self performSelector:@selector(nextArt) withObject:nil afterDelay:2];
		return;
	}
	[NSObject cancelPreviousPerformRequestsWithTarget:self selector:@selector(nextArt) object:nil];
	UIImageView *outgoing = _backgrounds[_front];
	_front = 1 - _front;
	UIImageView *incoming = _backgrounds[_front];
	incoming.image = image;
	incoming.transform = CGAffineTransformIdentity;
	[self sendSubviewToBack:incoming];
	[self insertSubview:outgoing aboveSubview:incoming];
	[UIView animateWithDuration:1.6 animations:^{
		incoming.alpha = 1;
		outgoing.alpha = 0;
	}];
	[UIView animateWithDuration:10.5 delay:0 options:UIViewAnimationOptionCurveLinear animations:^{
		incoming.transform = CGAffineTransformMakeScale(1.08, 1.08);
	} completion:nil];
}

- (void)nextTip
{
	NSArray *tips = loadingTips();
	NSString *tip = tips[(NSUInteger)_tipIndex % tips.count];
	_tipIndex++;
	[UIView transitionWithView:_tip duration:0.4 options:UIViewAnimationOptionTransitionCrossDissolve
		animations:^{ self->_tip.text = tip; } completion:nil];
}

- (void)showBody:(NSString *)body primary:(NSString *)primary secondary:(NSString *)secondary
{
	_body.text = body;
	_status.text = @"";
	_percent.hidden = YES;
	_bar.hidden = YES;
	_primary.hidden = primary == nil;
	_primary.enabled = YES;
	[_primary setTitle:primary.uppercaseString forState:UIControlStateNormal];
	_secondary.hidden = secondary == nil;
	[_secondary setTitle:secondary.uppercaseString forState:UIControlStateNormal];
}

- (void)startProgress
{
	_primary.hidden = YES;
	_secondary.hidden = YES;
	_percent.hidden = NO;
	_percent.text = @"";
	_bar.hidden = NO;
	_bar.indeterminate = YES;
	_lastDone = -1;
	_bytesPerSecond = 0;
	if (_tipTimer == nil) {
		_tipIndex = (int)arc4random_uniform((uint32_t)loadingTips().count);
		[self nextTip];
		_tipTimer = [NSTimer timerWithTimeInterval:6 target:self selector:@selector(nextTip) userInfo:nil repeats:YES];
		[NSRunLoop.mainRunLoop addTimer:_tipTimer forMode:NSRunLoopCommonModes];
	}
}

- (void)setIndeterminate:(BOOL)indeterminate
{
	_bar.indeterminate = indeterminate;
	if (indeterminate) {
		_percent.text = @"";
	}
}

// "812 / 2134 MB · 6.2 MB/s · 3 min left", the speed averaged over the samples. Plain %d
// formatting: Western digits whatever the device language (the Android app asks for nu=latn).
- (void)showDone:(long long)done total:(long long)total
{
	if (total <= 0) {
		return;
	}
	_bar.indeterminate = NO;
	_bar.fraction = (CGFloat)((double)done / (double)total);
	_percent.text = [NSString stringWithFormat:@"%d%%", (int)(done * 100 / total)];
	const CFTimeInterval now = CACurrentMediaTime();
	if (_lastDone >= 0 && now - _lastSample < 0.25) {
		return;  // a few samples a second are enough for the speed and the text
	}
	if (_lastDone >= 0 && now > _lastSample && done >= _lastDone) {
		const double sample = (double)(done - _lastDone) / (now - _lastSample);
		_bytesPerSecond = _bytesPerSecond <= 0 ? sample : _bytesPerSecond * 0.8 + sample * 0.2;
	}
	_lastDone = done;
	_lastSample = now;
	NSString *left = @"…";
	if (_bytesPerSecond > 1024) {
		const long long seconds = (long long)((double)(total - done) / _bytesPerSecond);
		left = seconds >= 90 ? [NSString stringWithFormat:@"%lld min", (seconds + 30) / 60]
							 : [NSString stringWithFormat:@"%lld s", MAX(1LL, seconds)];
	}
	_status.text = [NSString stringWithFormat:@"%lld / %lld MB  ·  %.1f MB/s  ·  %@ left",
		done >> 20, total >> 20, _bytesPerSecond / (1 << 20), left];
}

@end

// ---------------------------------------------------------------- the gate screen

@interface ZHGateController : UIViewController <UITextFieldDelegate>
@property (nonatomic) BOOL finished;
@end

@implementation ZHGateController {
	BOOL _started;
	UIView *_card;
	UILabel *_title;
	UILabel *_body;
	UITextField *_field;
	UIButton *_cardPrimary;
	UIButton *_cardSecondary;
	UILabel *_cardStatus;
	// The game-data screens (GeneralsX @feature ZH Commander 11/10/2026).
	ZHLoadingView *_loading;
	// The buttons and status line of whichever screen is up: the card's or the loading screen's.
	UIButton *_primary;
	UIButton *_secondary;
	UILabel *_status;
	NSString *_dataVersion;
	NSArray<ZHDataFile *> *_manifestFiles;
	NSArray<ZHDataFile *> *_todo;
	NSArray<NSString *> *_packsOn;  // the packs this install will include
}

// The landing site's look: sand ground, a canvas "crate label" card with an ink border and an
// olive offset shadow, stencil capitals for titles and buttons, IBM Plex Sans for text.
static UIColor *zhColor(uint32_t rgb)
{
	return [UIColor colorWithRed:((rgb >> 16) & 0xFF) / 255.0 green:((rgb >> 8) & 0xFF) / 255.0
		blue:(rgb & 0xFF) / 255.0 alpha:1];
}

- (void)viewDidLoad
{
	[super viewDidLoad];
	UIColor *sand = zhColor(0xD2C192);
	UIColor *canvas = zhColor(0xE4D8B3);
	UIColor *ink = zhColor(0x1D1F14);
	UIColor *drab = zhColor(0x4A5228);
	UIColor *signal = zhColor(0xC2461B);
	self.view.backgroundColor = sand;

	_title = [UILabel new];
	_title.font = zhFont(@"zh_display.ttf", 34, [UIFont boldSystemFontOfSize:28]);
	_title.textColor = ink;
	_title.numberOfLines = 0;
	_body = [UILabel new];
	_body.numberOfLines = 0;
	_body.textColor = ink;
	_body.font = zhFont(@"zh_body.ttf", 16, [UIFont systemFontOfSize:16]);
	_field = [UITextField new];
	_field.borderStyle = UITextBorderStyleNone;
	_field.backgroundColor = zhColor(0xF0E8D0);
	_field.layer.borderColor = drab.CGColor;
	_field.layer.borderWidth = 2;
	_field.layer.cornerRadius = 3;
	_field.leftView = [[UIView alloc] initWithFrame:CGRectMake(0, 0, 12, 1)];
	_field.leftViewMode = UITextFieldViewModeAlways;
	_field.font = zhFont(@"zh_body_semibold.ttf", 18, [UIFont boldSystemFontOfSize:18]);
	_field.textColor = ink;
	_field.placeholder = @"GZH-XXXX-XXXX-XXXX";
	_field.autocapitalizationType = UITextAutocapitalizationTypeAllCharacters;
	_field.autocorrectionType = UITextAutocorrectionTypeNo;
	_field.returnKeyType = UIReturnKeyGo;
	_field.delegate = self;
	_cardPrimary = [UIButton buttonWithType:UIButtonTypeSystem];
	_cardPrimary.backgroundColor = signal;
	[_cardPrimary setTitleColor:zhColor(0xFFF8EC) forState:UIControlStateNormal];
	_cardPrimary.titleLabel.font = zhFont(@"zh_display.ttf", 24, [UIFont boldSystemFontOfSize:20]);
	_cardPrimary.layer.cornerRadius = 3;
	_cardPrimary.layer.borderColor = ink.CGColor;
	_cardPrimary.layer.borderWidth = 3;
	[_cardPrimary addTarget:self action:@selector(onPrimary) forControlEvents:UIControlEventTouchUpInside];
	_cardSecondary = [UIButton buttonWithType:UIButtonTypeSystem];
	_cardSecondary.backgroundColor = canvas;
	[_cardSecondary setTitleColor:ink forState:UIControlStateNormal];
	_cardSecondary.titleLabel.font = zhFont(@"zh_display.ttf", 20, [UIFont boldSystemFontOfSize:18]);
	_cardSecondary.layer.cornerRadius = 3;
	_cardSecondary.layer.borderColor = drab.CGColor;
	_cardSecondary.layer.borderWidth = 2;
	[_cardSecondary addTarget:self action:@selector(onSecondary) forControlEvents:UIControlEventTouchUpInside];
	_cardStatus = [UILabel new];
	_cardStatus.numberOfLines = 0;
	_cardStatus.textColor = drab;
	_cardStatus.font = zhFont(@"zh_body.ttf", 14, [UIFont systemFontOfSize:14]);

	UIStackView *stack = [[UIStackView alloc] initWithArrangedSubviews:@[_title, _body, _field, _cardPrimary, _cardSecondary, _cardStatus]];
	stack.axis = UILayoutConstraintAxisVertical;
	stack.spacing = 14;
	stack.translatesAutoresizingMaskIntoConstraints = NO;

	// The card: canvas, ink border, and the site's hard olive offset shadow (no blur).
	UIView *card = [UIView new];
	card.backgroundColor = canvas;
	card.layer.borderColor = ink.CGColor;
	card.layer.borderWidth = 3;
	card.layer.shadowColor = drab.CGColor;
	card.layer.shadowOffset = CGSizeMake(8, 8);
	card.layer.shadowOpacity = 1;
	card.layer.shadowRadius = 0;
	card.translatesAutoresizingMaskIntoConstraints = NO;
	[card addSubview:stack];
	[self.view addSubview:card];
	_card = card;
	UILayoutGuide *g = self.view.safeAreaLayoutGuide;
	[NSLayoutConstraint activateConstraints:@[
		[card.centerXAnchor constraintEqualToAnchor:g.centerXAnchor],
		[card.centerYAnchor constraintEqualToAnchor:g.centerYAnchor],
		[card.leadingAnchor constraintGreaterThanOrEqualToAnchor:g.leadingAnchor constant:24],
		[card.trailingAnchor constraintLessThanOrEqualToAnchor:g.trailingAnchor constant:-32],
		[card.topAnchor constraintGreaterThanOrEqualToAnchor:g.topAnchor constant:16],
		[card.bottomAnchor constraintLessThanOrEqualToAnchor:g.bottomAnchor constant:-24],
		[stack.topAnchor constraintEqualToAnchor:card.topAnchor constant:24],
		[stack.bottomAnchor constraintEqualToAnchor:card.bottomAnchor constant:-24],
		[stack.leadingAnchor constraintEqualToAnchor:card.leadingAnchor constant:24],
		[stack.trailingAnchor constraintEqualToAnchor:card.trailingAnchor constant:-24],
		[stack.widthAnchor constraintLessThanOrEqualToConstant:520],
		[_field.heightAnchor constraintEqualToConstant:48],
		[_cardPrimary.heightAnchor constraintEqualToConstant:54],
		[_cardSecondary.heightAnchor constraintEqualToConstant:46],
	]];
	NSLayoutConstraint *wide = [stack.widthAnchor constraintEqualToConstant:520];
	wide.priority = UILayoutPriorityDefaultHigh;
	wide.active = YES;

	// The game-data screens: the whole view, over the card, shown by showData.
	_loading = [[ZHLoadingView alloc] initWithFrame:self.view.bounds];
	_loading.translatesAutoresizingMaskIntoConstraints = NO;
	_loading.hidden = YES;
	[_loading.primary addTarget:self action:@selector(onPrimary) forControlEvents:UIControlEventTouchUpInside];
	[_loading.secondary addTarget:self action:@selector(onSecondary) forControlEvents:UIControlEventTouchUpInside];
	[self.view addSubview:_loading];
	[NSLayoutConstraint activateConstraints:@[
		[_loading.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor],
		[_loading.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor],
		[_loading.topAnchor constraintEqualToAnchor:self.view.topAnchor],
		[_loading.bottomAnchor constraintEqualToAnchor:self.view.bottomAnchor],
	]];

	[self show:@"ZH Commander" body:@"Checking…" primary:nil secondary:nil field:NO];
}

// The checks wait on the network, and the screen is only on display once this view has
// appeared: started any earlier (viewDidLoad), they left the player looking at a black window.
// GeneralsX @bugfix ZH Commander 11/10/2026 Started from a timer, not a main-queue block: the
// checks turn the run loop while they wait, and a run loop turned inside a main-queue block does
// not drain the main queue, so whatever the system or this file queued there stood still.
- (void)viewDidAppear:(BOOL)animated
{
	[super viewDidAppear:animated];
	if (_started) {
		return;
	}
	_started = YES;
	[self performSelector:@selector(step) withObject:nil afterDelay:0];
}

// Decide what the player needs next: a license, then the game data.
- (void)step
{
	if ([self updateRequired]) {
		return;
	}
	if (storedLicense() == nil) {
		[self showActivation];
		return;
	}
	[self checkData];
}

// Releases are required: an app older than the newest one in the AltStore source does not
// start the game. It cannot update itself on iOS -- SideStore/AltStore install the update --
// so it says so and opens SideStore. Offline it lets the player through.
- (BOOL)updateRequired
{
	NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:
		[NSURL URLWithString:[kSite stringByAppendingString:@"/altstore.json"]]];
	req.timeoutInterval = 8;
	req.cachePolicy = NSURLRequestReloadIgnoringLocalCacheData;
	NSInteger status = 0;
	NSData *data = runRequest(req, &status);
	NSDictionary *source = data && status == 200 ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
	NSArray *apps = [source isKindOfClass:NSDictionary.class] ? source[@"apps"] : nil;
	NSDictionary *app = [apps isKindOfClass:NSArray.class] ? apps.firstObject : nil;
	NSArray *versions = [app isKindOfClass:NSDictionary.class] ? app[@"versions"] : nil;
	NSDictionary *entry = [versions isKindOfClass:NSArray.class] && versions.count > 0 ? versions.firstObject : nil;
	NSString *latest = [entry isKindOfClass:NSDictionary.class] ? entry[@"version"] : nil;
	NSString *mine = NSBundle.mainBundle.infoDictionary[@"CFBundleShortVersionString"];
	if (![latest isKindOfClass:NSString.class] || mine == nil || !isNewerRelease(entry)) {
		return NO;
	}
	[self show:@"Update required"
		  body:[NSString stringWithFormat:@"ZH Commander %@ is available and required to play (you have %@). Open SideStore or AltStore and update ZH Commander, then open the game again.", latest, mine]
	   primary:@"Open SideStore" secondary:@"Check again" field:NO];
	_primary.tag = 4;
	_secondary.tag = 4;
	return YES;
}

// A screen on the card (activation, a required update).
- (void)show:(NSString *)title body:(NSString *)body primary:(NSString *)primary secondary:(NSString *)secondary field:(BOOL)field
{
	_loading.hidden = YES;
	_card.hidden = NO;
	_primary = _cardPrimary;
	_secondary = _cardSecondary;
	_status = _cardStatus;
	_title.text = title.uppercaseString;  // stencil capitals, like the site
	_body.text = body;
	_primary.tag = 0;  // each screen sets the actions it wants after calling show
	_secondary.tag = 0;
	_field.hidden = !field;
	_primary.hidden = primary == nil;
	[_primary setTitle:primary.uppercaseString forState:UIControlStateNormal];
	_primary.enabled = YES;
	_secondary.hidden = secondary == nil;
	[_secondary setTitle:secondary.uppercaseString forState:UIControlStateNormal];
	_status.text = @"";
}

// A game-data screen, on the loading screen (DataDownloadActivity's look).
- (void)showData:(NSString *)subtitle body:(NSString *)body primary:(NSString *)primary secondary:(NSString *)secondary
{
	[_field resignFirstResponder];
	_card.hidden = YES;
	_loading.hidden = NO;
	_primary = _loading.primary;
	_secondary = _loading.secondary;
	_status = _loading.status;
	_primary.tag = 0;
	_secondary.tag = 0;
	[_loading setSubtitleText:subtitle];
	[_loading showBody:body primary:primary secondary:secondary];
}

- (void)showActivation
{
	[self show:@"Activate this device"
		  body:@"Copy the activation key you were given, then tap Paste key. It works on one device only; this device is checked once, online, and then plays offline."
	   primary:@"Activate" secondary:@"Paste key" field:YES];
	_primary.tag = 1;
	_secondary.tag = 5;
}

// Activation without the on-screen keyboard: the key straight from the clipboard.
- (void)pasteKey
{
	NSString *pasted = UIPasteboard.generalPasteboard.string;
	if (pasted.length == 0) {
		_status.text = @"Nothing to paste: copy your activation key first.";
		return;
	}
	_field.text = [pasted stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
	[self activate];
}

- (BOOL)textFieldShouldReturn:(UITextField *)textField
{
	[self onPrimary];
	return YES;
}

- (void)onPrimary
{
	switch (_primary.tag) {
		case 1: [self activate]; break;
		case 2: [self download]; break;
		case 3: [self checkData]; break;
		case 4: [self openStore]; break;
		default: break;
	}
}

- (void)onSecondary
{
	if (_secondary.tag == 4) {
		[self step];  // "Check again" after updating in SideStore
		return;
	}
	if (_secondary.tag == 5) {
		[self pasteKey];
		return;
	}
	[self dataReady];  // "Later" on a data update: go on to the game
}

- (void)openStore
{
	// SideStore first, then AltStore; whichever the player installed answers.
	UIApplication *app = UIApplication.sharedApplication;
	NSURL *sidestore = [NSURL URLWithString:@"sidestore://"];
	NSURL *altstore = [NSURL URLWithString:@"altstore://"];
	[app openURL:sidestore options:@{} completionHandler:^(BOOL opened) {
		if (!opened) {
			[app openURL:altstore options:@{} completionHandler:nil];
		}
	}];
}

- (void)activate
{
	NSString *code = [_field.text stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
	if (code.length == 0) {
		_status.text = @"That key is not valid. Check it and try again.";
		return;
	}
	[_field resignFirstResponder];
	_primary.enabled = NO;
	_status.text = @"Activating…";
	NSString *device = deviceHash();
	NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:kActivateURL]];
	req.HTTPMethod = @"POST";
	[req setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
	req.HTTPBody = [NSJSONSerialization dataWithJSONObject:@{@"code": code, @"device": device} options:0 error:nil];
	req.timeoutInterval = 20;
	NSInteger status = 0;
	NSData *data = runRequest(req, &status);
	_primary.enabled = YES;
	NSDictionary *reply = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
	if (data == nil) {
		_status.text = @"Could not reach the activation server. Check your internet connection.";
		return;
	}
	NSString *license = [reply isKindOfClass:NSDictionary.class] ? reply[@"license"] : nil;
	if (status == 200 && [license isKindOfClass:NSString.class] && licenseValid(license, device)) {
		[NSUserDefaults.standardUserDefaults setObject:license forKey:kLicenseKey];
		[self checkData];
		return;
	}
	NSString *error = [reply isKindOfClass:NSDictionary.class] ? reply[@"error"] : nil;
	if ([error isEqual:@"key_used"]) {
		_status.text = @"That key has already been used on another device.";
	} else if ([error isEqual:@"revoked_key"]) {
		_status.text = @"That key has been withdrawn.";
	} else if ([error isEqual:@"invalid_key"] || [error isEqual:@"bad_request"]) {
		_status.text = @"That key is not valid. Check it and try again.";
	} else {
		_status.text = @"The activation server had a problem. Try again later.";
	}
}

- (void)checkData
{
	[self showData:@"Game data" body:@"Checking for game data…" primary:nil secondary:nil];
	NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:
		[NSURL URLWithString:[kSite stringByAppendingString:@"/api/data/manifest"]]];
	[req setValue:storedLicense() forHTTPHeaderField:@"X-ZH-License"];
	req.timeoutInterval = 20;
	NSInteger status = 0;
	NSData *data = runRequest(req, &status);
	NSDictionary *json = data && status == 200 ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
	if (![json isKindOfClass:NSDictionary.class]) {
		if (haveGameData()) {
			[self dataReady];  // offline or nothing published: play what is installed
			return;
		}
		[self showData:@"Game data"
				  body:(data == nil ? @"Couldn't reach the download server. Check your internet connection."
									: @"No game data has been published yet. Try again later.")
			   primary:@"Try again" secondary:nil];
		_primary.tag = 3;
		return;
	}
	// The base files, then those of every optional pack (mod) the player turned on (ZHMods).
	[NSUserDefaults.standardUserDefaults setObject:data forKey:kManifestDefaultsKey];
	NSSet *enabled = enabledPacks();
	NSMutableArray *wanted = [NSMutableArray arrayWithArray:json[@"files"] ?: @[]];
	NSMutableArray *packsOn = [NSMutableArray array];
	for (NSDictionary *pack in json[@"packs"]) {
		if ([pack isKindOfClass:NSDictionary.class] && [enabled containsObject:pack[@"id"]]
			&& [pack[@"files"] isKindOfClass:NSArray.class]) {
			[wanted addObjectsFromArray:pack[@"files"]];
			[packsOn addObject:pack[@"id"]];
		}
	}
	_packsOn = packsOn;
	NSMutableArray *files = [NSMutableArray array];
	for (NSDictionary *f in wanted) {
		ZHDataFile *e = [ZHDataFile new];
		e.path = f[@"path"];
		e.sha256 = [f[@"sha256"] lowercaseString];
		e.key = f[@"key"];
		e.size = [f[@"size"] longLongValue];
		if (e.path.length == 0 || [e.path containsString:@".."] || [e.path hasPrefix:@"/"]) {
			continue;
		}
		[files addObject:e];
	}
	_dataVersion = json[@"version"];
	_manifestFiles = files;
	NSDictionary *installed = installedFiles();
	NSDictionary *finished = finishedFiles(_dataVersion);
	NSMutableArray *todo = [NSMutableArray array];
	long long bytes = 0;
	for (ZHDataFile *e in files) {
		NSString *local = [documentsDir() stringByAppendingPathComponent:e.path];
		NSDictionary *attrs = [NSFileManager.defaultManager attributesOfItemAtPath:local error:nil];
		BOOL have = attrs != nil && [attrs fileSize] == (unsigned long long)e.size
			&& ([installed[e.path] isEqual:e.sha256] || [finished[e.path] isEqual:e.sha256]);
		if (!have) {
			[todo addObject:e];
			bytes += e.size;
		}
	}
	_todo = todo;
	// An interrupted download continues by itself: what finished is kept, the unfinished file
	// resumes where it stopped.
	if (downloadIncomplete()) {
		[self showData:@"Download the game data" body:@"Resuming the download…" primary:@"Download" secondary:nil];
		_primary.tag = 2;
		[self download];
		return;
	}
	if (todo.count == 0) {
		NSArray *installedPacks = readState()[@"packs"] ?: @[];
		if (![_dataVersion isEqual:readState()[@"version"]]
			|| ![[NSSet setWithArray:installedPacks] isEqualToSet:[NSSet setWithArray:_packsOn ?: @[]]]) {
			[self commitData];  // a release that only withdraws files
			return;
		}
		[self dataReady];
		return;
	}
	BOOL update = haveGameData();
	NSString *body = update
		? [NSString stringWithFormat:@"New game data is available (version %@, about %lld MB to download). Only the files that changed are downloaded.", _dataVersion, MAX(1LL, bytes >> 20)]
		: [NSString stringWithFormat:@"ZH Commander needs its game data to play (version %@, about %lld MB). It downloads once, straight into the game; use Wi-Fi if you can.", _dataVersion, MAX(1LL, bytes >> 20)];
	[self showData:@"Download the game data" body:body primary:@"Download" secondary:(update ? @"Later" : nil)];
	_primary.tag = 2;
	NSNumber *free = [[NSFileManager.defaultManager attributesOfFileSystemForPath:documentsDir() error:nil]
		objectForKey:NSFileSystemFreeSize];
	if (free != nil && free.longLongValue < bytes + (300LL << 20)) {
		_status.text = [NSString stringWithFormat:@"Not enough free space: free up about %lld MB more first.",
			((bytes + (300LL << 20)) - free.longLongValue) >> 20];
	}
}

- (void)download
{
	long long total = 0;
	for (ZHDataFile *e in _todo) {
		total += e.size;
	}
	[_loading showBody:@"Keep ZH Commander open while it downloads; if it is interrupted, the next start continues where it stopped."
		primary:nil secondary:nil];
	[_loading startProgress];
	long long doneBytes = 0;
	NSFileManager *fm = NSFileManager.defaultManager;
	NSString *docs = documentsDir();
	NSString *license = storedLicense();
	// Recorded before the first byte and after every file, so the next start knows a download is
	// underway and which files it already has.
	NSMutableDictionary *finished = [finishedFiles(_dataVersion) mutableCopy];
	writeProgress(_dataVersion, finished);
	[UIApplication.sharedApplication setIdleTimerDisabled:YES];  // no auto-lock while downloading
	for (ZHDataFile *e in _todo) {
		NSString *target = [docs stringByAppendingPathComponent:e.path];
		[fm createDirectoryAtPath:target.stringByDeletingLastPathComponent withIntermediateDirectories:YES attributes:nil error:nil];
		NSString *part = [target stringByAppendingString:@".part"];
		// A dropped connection is retried before giving up; each attempt resumes the .part file.
		BOOL fetched = NO;
		for (int attempt = 0; attempt < 5 && !fetched; attempt++) {
			if (attempt > 0) {
				NSDate *until = [NSDate dateWithTimeIntervalSinceNow:(attempt == 1 ? 2 : attempt == 2 ? 5 : 15)];
				spinUntil(^BOOL { return [until timeIntervalSinceNow] <= 0; });
			}
			fetched = [self fetch:e to:part license:license before:doneBytes total:total];
		}
		if (!fetched) {
			[UIApplication.sharedApplication setIdleTimerDisabled:NO];
			[_loading showBody:@"The download stopped. Tap Download to continue where it left off."
				primary:@"Download" secondary:nil];
			_primary.tag = 2;
			return;
		}
		[fm removeItemAtPath:target error:nil];
		if (![fm moveItemAtPath:part toPath:target error:nil]) {
			[UIApplication.sharedApplication setIdleTimerDisabled:NO];
			[_loading showBody:@"Couldn't save the game data. Free up some space and try again."
				primary:@"Download" secondary:nil];
			_primary.tag = 2;
			return;
		}
		finished[e.path] = e.sha256;
		writeProgress(_dataVersion, finished);
		doneBytes += e.size;
	}
	[UIApplication.sharedApplication setIdleTimerDisabled:NO];
	[self commitData];
}

// Makes the manifest's version the installed one: removes what the previous version installed
// and this one no longer lists (a withdrawn mod or asset), records the version. Also what a
// release that only removes files needs, with nothing to download.
- (void)commitData
{
	NSFileManager *fm = NSFileManager.defaultManager;
	NSString *docs = documentsDir();
	NSMutableDictionary *previous = [installedFiles() mutableCopy];
	for (ZHDataFile *e in _manifestFiles) {
		[previous removeObjectForKey:e.path];
	}
	for (NSString *obsolete in previous) {
		[fm removeItemAtPath:[docs stringByAppendingPathComponent:obsolete] error:nil];
	}
	NSMutableArray *state = [NSMutableArray array];
	for (ZHDataFile *e in _manifestFiles) {
		[state addObject:@{@"path": e.path, @"sha256": e.sha256, @"size": @(e.size)}];
	}
	NSData *json = [NSJSONSerialization dataWithJSONObject:@{@"version": _dataVersion ?: @"", @"files": state,
		@"packs": _packsOn ?: @[]} options:0 error:nil];
	[json writeToFile:[docs stringByAppendingPathComponent:kStateFile] atomically:YES];
	[fm removeItemAtPath:progressPath() error:nil];
	[self dataReady];
}

- (BOOL)fetch:(ZHDataFile *)e to:(NSString *)part license:(NSString *)license before:(long long)before total:(long long)total
{
	NSFileManager *fm = NSFileManager.defaultManager;
	if (![fm fileExistsAtPath:part]) {
		[fm createFileAtPath:part contents:nil attributes:nil];
	}
	long long have = (long long)[[fm attributesOfItemAtPath:part error:nil] fileSize];
	if (have > e.size) {
		[[NSData data] writeToFile:part atomically:NO];
		have = 0;
	}
	if (have < e.size) {
		NSMutableArray *segments = [NSMutableArray array];
		for (NSString *seg in [e.key componentsSeparatedByString:@"/"]) {
			[segments addObject:[seg stringByAddingPercentEncodingWithAllowedCharacters:NSCharacterSet.URLPathAllowedCharacterSet]];
		}
		NSString *url = [NSString stringWithFormat:@"%@/%@", kSite, [segments componentsJoinedByString:@"/"]];
		NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:url]];
		[req setValue:license forHTTPHeaderField:@"X-ZH-License"];
		if (have > 0) {
			[req setValue:[NSString stringWithFormat:@"bytes=%lld-", have] forHTTPHeaderField:@"Range"];
		}
		req.timeoutInterval = 60;
		ZHDownloader *dl = [ZHDownloader new];
		dl.handle = [NSFileHandle fileHandleForWritingAtPath:part];
		[dl.handle seekToEndOfFile];
		dl.received = have;
		NSURLSession *session = [NSURLSession sessionWithConfiguration:NSURLSessionConfiguration.defaultSessionConfiguration
			delegate:dl delegateQueue:nil];
		[[session dataTaskWithRequest:req] resume];
		ZHLoadingView *loading = _loading;
		spinUntil(^BOOL {
			[loading showDone:before + dl.received total:total];
			return dl.done;
		});
		[session finishTasksAndInvalidate];
		[dl.handle closeFile];
		if (dl.failed) {
			return NO;
		}
	}
	long long size = (long long)[[fm attributesOfItemAtPath:part error:nil] fileSize];
	// Hashed off the main thread: a large archive takes seconds, and the screen stays live.
	NSString *sha = size == e.size ? runInBackground(^id { return sha256OfFile(part); }) : nil;
	if (![sha isEqual:e.sha256]) {
		[fm removeItemAtPath:part error:nil];
		return NO;
	}
	return YES;
}

// GeneralsX @feature ZH Commander 11/10/2026 The game data is in place; what online play needs
// comes next, with nothing for the player to set up (ZeroSetup.java): the cross-play marker and
// the community data's switch follow "Play with PC players", the community data is fetched when
// it is missing or GeneralsOnline published a newer one, and the GeneralsOnline session is renewed
// while the engine is not running. None of it keeps the game from starting.
- (void)dataReady
{
	ensureCrossPlay();
	if (crossPlayWanted()) {
		[self showData:@"GeneralsOnline" body:@"Checking the GeneralsOnline data…" primary:nil secondary:nil];
		NSDictionary *manifest = runInBackground(^id { return fetchPatchManifest(8); });
		NSString *latest = [manifest[@"version"] description];
		NSString *installed = patchInstalledVersion();
		if (installed == nil || (latest.length > 0 && ![latest isEqualToString:installed])) {
			[self installPatch];
		}
	}
	if (readSessionMarker()[@"refresh_token"].length > 0) {
		[self showData:@"GeneralsOnline" body:@"Signing in to GeneralsOnline…" primary:nil secondary:nil];
		refreshSessionBeforeGame();
	}
	self.finished = YES;
}

- (void)installPatch
{
	[self showData:@"GeneralsOnline"
			  body:@"Getting the GeneralsOnline data, so you can play online with PC players and phones alike. This happens once, and again when GeneralsOnline updates it."
		   primary:nil secondary:nil];
	[_loading startProgress];
	ZHPatchProgress *progress = [ZHPatchProgress new];
	__block NSString *failure = nil;
	__block BOOL ended = NO;
	dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
		NSString *result = installCommunityPatch(progress);
		@synchronized (progress) {
			failure = result;
			ended = YES;
		}
	});
	ZHLoadingView *loading = _loading;
	spinUntil(^BOOL {
		@synchronized (progress) {
			if (ended) {
				return YES;
			}
		}
		if (progress.phase == 1) {
			[loading showDone:progress.done total:progress.total];
		} else {
			[loading setIndeterminate:YES];
		}
		return NO;
	});
	if (failure != nil) {
		// The game starts anyway; the next start tries again.
		fprintf(stderr, "WARNING: GeneralsOnline data not installed: %s\n", failure.UTF8String);
		[_loading showBody:[failure stringByAppendingString:@" The game starts without it; the next start tries again."]
			primary:nil secondary:nil];
		NSDate *until = [NSDate dateWithTimeIntervalSinceNow:3];
		spinUntil(^BOOL { return [until timeIntervalSinceNow] <= 0; });
	}
}

- (UIInterfaceOrientationMask)supportedInterfaceOrientations
{
	return UIInterfaceOrientationMaskLandscape;
}

@end

extern "C" bool ZHIOSRunGate(void)
{
	@autoreleasepool {
		// On current iOS a window is shown only when it belongs to the app's window scene;
		// one created with just a frame can stay invisible, leaving the screen black.
		UIWindowScene *scene = nil;
		for (UIScene *s in UIApplication.sharedApplication.connectedScenes) {
			if ([s isKindOfClass:UIWindowScene.class]) {
				scene = (UIWindowScene *)s;
				if (s.activationState == UISceneActivationStateForegroundActive) {
					break;
				}
			}
		}
		UIWindow *window = scene != nil ? [[UIWindow alloc] initWithWindowScene:scene]
			: [[UIWindow alloc] initWithFrame:UIScreen.mainScreen.bounds];
		window.frame = scene != nil ? scene.coordinateSpace.bounds : UIScreen.mainScreen.bounds;
		ZHGateController *gate = [ZHGateController new];
		window.rootViewController = gate;
		window.windowLevel = UIWindowLevelAlert;
		[window makeKeyAndVisible];
		while (!gate.finished) {
			[NSRunLoop.currentRunLoop runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
		}
		window.hidden = YES;
		window.rootViewController = nil;
	}
	return true;
}

// ---------------------------------------------------------------- hooks for the game's menus

// The game's own menus (MainMenu.cpp, CommanderMenu.cpp) show the version, offer updates and the
// support report through ZHCommander.h. The engine runs on the main thread, as these do.

@interface ZHSupport : NSObject
+ (UIWindow *)gameWindow;  // also used by the mods screen
@end

@implementation ZHSupport

+ (UIWindow *)gameWindow
{
	for (UIWindow *w in UIApplication.sharedApplication.windows) {
		if (!w.hidden && w.rootViewController != nil && w.windowLevel == UIWindowLevelNormal) {
			return w;
		}
	}
	return nil;
}

+ (NSString *)versionLine
{
	NSDictionary *info = NSBundle.mainBundle.infoDictionary;
	return [NSString stringWithFormat:@"ZH Commander %@ (%@)\nGame data %@",
		info[@"CFBundleShortVersionString"], info[@"CFBundleVersion"], readState()[@"version"] ?: @"none"];
}

+ (void)shareReport
{
	UIViewController *host = [self gameWindow].rootViewController;
	if (host == nil) {
		return;
	}
	NSMutableString *report = [NSMutableString stringWithString:@"ZH Commander support report\n\n"];
	[report appendFormat:@"%@\n", [self versionLine]];
	UIDevice *d = UIDevice.currentDevice;
	[report appendFormat:@"Device: %@, %@ %@\n", d.model, d.systemName, d.systemVersion];
	[report appendFormat:@"Activated: %@ (device %@)\n", storedLicense() ? @"yes" : @"no", [deviceHash() substringToIndex:12]];
	NSNumber *free = [[NSFileManager.defaultManager attributesOfFileSystemForPath:documentsDir() error:nil]
		objectForKey:NSFileSystemFreeSize];
	[report appendFormat:@"Storage free: %lld MB\n", free.longLongValue >> 20];
	[report appendString:[self onlineSection]];
	[report appendString:@"\nGame data files:\n"];
	NSMutableArray *items = [NSMutableArray array];
	NSArray *names = [NSFileManager.defaultManager contentsOfDirectoryAtPath:documentsDir() error:nil];
	for (NSString *name in [names sortedArrayUsingSelector:@selector(compare:)]) {
		NSString *path = [documentsDir() stringByAppendingPathComponent:name];
		unsigned long long size = [[NSFileManager.defaultManager attributesOfItemAtPath:path error:nil] fileSize];
		if ([name.pathExtension.lowercaseString isEqual:@"big"]) {
			[report appendFormat:@"  %@  %llu bytes\n", name, size];
		}
		if ([name.pathExtension.lowercaseString isEqual:@"log"] || [name hasSuffix:@".log.prev"]) {
			[items addObject:[NSURL fileURLWithPath:path]];
		}
	}
	NSString *reportPath = [NSTemporaryDirectory() stringByAppendingPathComponent:@"support-report.txt"];
	[report writeToFile:reportPath atomically:YES encoding:NSUTF8StringEncoding error:nil];
	[items insertObject:[NSURL fileURLWithPath:reportPath] atIndex:0];
	[self upload:items host:host];
}

// GeneralsX @feature ZH Commander 11/10/2026 Cross-play's state and the checksums a lobby compares,
// as the Android report's "Online" section: signed in, the switch, the community data and its PC
// checksum seed, then the engine's [GX-CRC] line and its last five lobby comparisons (numbers
// only: the lobby names are other players') from the newest log that has them.
+ (NSString *)onlineSection
{
	NSMutableString *s = [NSMutableString stringWithString:@"\n== Online ==\n"];
	[s appendFormat:@"Signed in: %@\n", goSignedInName() != nil ? @"true" : @"false"];
	[s appendFormat:@"Game speed: %d Hz (this build's only engine)\n", 60];
	[s appendFormat:@"Play with PC players: %@\n", [NSFileManager.defaultManager fileExistsAtPath:
		[documentsDir() stringByAppendingPathComponent:kCrossPlayMarker]] ? @"true" : @"false"];
	NSString *patch = patchInstalledVersion();
	[s appendFormat:@"GeneralsOnline data: %@%@, PC checksum seed: %@\n", patch ?: @"not installed",
		patchEnabled() ? @"" : @" (switched off)", hasPcExeCrcSeed() ? @"true" : @"false"];
	NSRegularExpression *join = [NSRegularExpression regularExpressionWithPattern:
		@"host exe_crc=(\\d+) ini_crc=(\\d+), local exe_crc=(\\d+) ini_crc=(\\d+)" options:0 error:nil];
	for (NSString *name in @[@"generals-stderr.log", @"generals-stderr-prev.log"]) {
		NSString *log = [NSString stringWithContentsOfFile:[documentsDir() stringByAppendingPathComponent:name]
			encoding:NSUTF8StringEncoding error:nil];
		if (log == nil) {
			continue;
		}
		NSString *crcLine = nil;
		NSMutableArray<NSString *> *joins = [NSMutableArray array];
		for (NSString *line in [log componentsSeparatedByString:@"\n"]) {
			if ([line hasPrefix:@"[GX-CRC] ini_crc="]) {
				crcLine = line;
			} else if ([line hasPrefix:@"DEBUG-JOIN: CRC check"]) {
				NSTextCheckingResult *m = [join firstMatchInString:line options:0 range:NSMakeRange(0, line.length)];
				if (m != nil) {
					[joins addObject:[NSString stringWithFormat:@"join: host exe %@ ini %@, this device exe %@ ini %@",
						[line substringWithRange:[m rangeAtIndex:1]], [line substringWithRange:[m rangeAtIndex:2]],
						[line substringWithRange:[m rangeAtIndex:3]], [line substringWithRange:[m rangeAtIndex:4]]]];
					if (joins.count > 5) {
						[joins removeObjectAtIndex:0];
					}
				}
			}
		}
		if (crcLine == nil && joins.count == 0) {
			continue;
		}
		[s appendFormat:@"From %@:\n", name];
		if (crcLine != nil) {
			[s appendFormat:@"%@\n", crcLine];
		}
		for (NSString *j in joins) {
			[s appendFormat:@"%@\n", j];
		}
		break;
	}
	return s;
}

// The report goes to the developer's server (site/src/support.ts) as one zip, and the player
// gets the reference it answers. The zip comes from the system: a coordinated read "for
// uploading" of a folder hands back that folder zipped. A failed upload offers the share sheet.
+ (void)upload:(NSArray<NSURL *> *)items host:(UIViewController *)host
{
	NSString *dir = [NSTemporaryDirectory() stringByAppendingPathComponent:@"zh-report"];
	[NSFileManager.defaultManager removeItemAtPath:dir error:nil];
	[NSFileManager.defaultManager createDirectoryAtPath:dir withIntermediateDirectories:YES attributes:nil error:nil];
	for (NSURL *item in items) {
		[NSFileManager.defaultManager copyItemAtPath:item.path
			toPath:[dir stringByAppendingPathComponent:item.lastPathComponent] error:nil];
	}
	__block NSData *zip = nil;
	NSError *coordError = nil;
	[[NSFileCoordinator new] coordinateReadingItemAtURL:[NSURL fileURLWithPath:dir]
		options:NSFileCoordinatorReadingForUploading error:&coordError byAccessor:^(NSURL *zipURL) {
			zip = [NSData dataWithContentsOfURL:zipURL];
		}];
	NSString *license = storedLicense();
	if (zip == nil || license == nil) {
		[self share:items host:host];
		return;
	}
	NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:
		[NSURL URLWithString:[kSite stringByAppendingString:@"/api/support/report"]]];
	req.HTTPMethod = @"POST";
	req.timeoutInterval = 60;
	[req setValue:@"application/zip" forHTTPHeaderField:@"Content-Type"];
	[req setValue:license forHTTPHeaderField:@"X-ZH-License"];
	[req setValue:deviceHash() forHTTPHeaderField:@"X-ZH-Device"];
	[req setValue:@"ios" forHTTPHeaderField:@"X-ZH-Platform"];
	[req setValue:NSBundle.mainBundle.infoDictionary[@"CFBundleShortVersionString"] forHTTPHeaderField:@"X-ZH-Version"];
	[req setValue:UIDevice.currentDevice.model forHTTPHeaderField:@"X-ZH-Model"];
	[[NSURLSession.sharedSession uploadTaskWithRequest:req fromData:zip
		completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
			NSInteger status = [response isKindOfClass:NSHTTPURLResponse.class] ? ((NSHTTPURLResponse *)response).statusCode : 0;
			NSDictionary *json = data && status == 200 ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
			NSString *ref = [json isKindOfClass:NSDictionary.class] ? json[@"ref"] : nil;
			dispatch_async(dispatch_get_main_queue(), ^{
				if (![ref isKindOfClass:NSString.class]) {
					[self share:items host:host];
					return;
				}
				UIAlertController *done = [UIAlertController alertControllerWithTitle:@"Report sent"
					message:[NSString stringWithFormat:@"Your reference: %@", ref]
					preferredStyle:UIAlertControllerStyleAlert];
				[done addAction:[UIAlertAction actionWithTitle:@"OK" style:UIAlertActionStyleDefault handler:nil]];
				[host presentViewController:done animated:YES completion:nil];
			});
		}] resume];
}

+ (void)share:(NSArray<NSURL *> *)items host:(UIViewController *)host
{
	UIActivityViewController *share = [[UIActivityViewController alloc] initWithActivityItems:items applicationActivities:nil];
	// iPad presents the sheet as a popover, which needs an anchor: the middle of the screen.
	UIView *view = host.view;
	share.popoverPresentationController.sourceView = view;
	share.popoverPresentationController.sourceRect = CGRectMake(CGRectGetMidX(view.bounds), CGRectGetMidY(view.bounds), 1, 1);
	share.popoverPresentationController.permittedArrowDirections = 0;
	[host presentViewController:share animated:YES completion:nil];
}

@end

// What the checks below found while the game runs: the gate already made sure the app and the
// data were current when it started, so these only change when something is published mid-game.
static NSDictionary *s_latestApp;  // the newest entry of the AltStore source
static NSString *s_latestData;
static NSDictionary *s_latestNotes;  // the newest release's notes (/api/notes/<version>)

// Release notes as the game shows them: the English section, "• " before each line.
static bool notesInto(NSDictionary *notes, char *title, int titleSize, char *body, int bodySize)
{
	NSDictionary *section = [notes isKindOfClass:NSDictionary.class] ? notes[@"en"] : nil;
	NSArray *items = [section isKindOfClass:NSDictionary.class] ? section[@"items"] : nil;
	if (![items isKindOfClass:NSArray.class] || items.count == 0) {
		return false;
	}
	NSMutableArray *lines = [NSMutableArray array];
	for (id item in items) {
		[lines addObject:[@"• " stringByAppendingString:[item description]]];
	}
	snprintf(title, titleSize, "%s", [[section[@"title"] description] UTF8String] ?: "");
	snprintf(body, bodySize, "%s", [[lines componentsJoinedByString:@"\n"] UTF8String]);
	return true;
}

// This version's notes (bundled as <app>/whatsnew.json by package-ios-zh.sh), once after an update.
static bool iosWhatsNew(char *title, int titleSize, char *body, int bodySize)
{
	NSString *mine = NSBundle.mainBundle.infoDictionary[@"CFBundleShortVersionString"];
	NSUserDefaults *defaults = NSUserDefaults.standardUserDefaults;
	NSString *seen = [defaults stringForKey:@"zh.lastVersion"];
	if (mine == nil || [mine isEqual:seen]) {
		return false;
	}
	[defaults setObject:mine forKey:@"zh.lastVersion"];
	if (seen == nil) {
		return false;  // first run of this install, or of a version before notes existed
	}
	NSString *path = [NSBundle.mainBundle pathForResource:@"whatsnew" ofType:@"json"];
	NSData *data = path ? [NSData dataWithContentsOfFile:path] : nil;
	NSDictionary *notes = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
	return [notes isKindOfClass:NSDictionary.class] && [notes[@"version"] isEqual:mine]
		&& notesInto(notes, title, titleSize, body, bodySize);
}

// ---------------------------------------------------------------- mods (optional packs)

// One switch per published pack. iOS apps cannot restart themselves, so a change is saved and
// the next start (ZHIOSRunGate's checkData) downloads the packs turned on and removes the rest.
@interface ZHModsController : UIViewController
@end

@implementation ZHModsController {
	NSMutableDictionary<NSString *, UISwitch *> *_switches;
}

- (void)viewDidLoad
{
	[super viewDidLoad];
	self.view.backgroundColor = [UIColor colorWithRed:0xD2 / 255.0 green:0xC1 / 255.0 blue:0x92 / 255.0 alpha:1];
	_switches = [NSMutableDictionary dictionary];
	UIStackView *stack = [UIStackView new];
	stack.axis = UILayoutConstraintAxisVertical;
	stack.spacing = 14;
	stack.translatesAutoresizingMaskIntoConstraints = NO;
	UIScrollView *scroll = [UIScrollView new];
	scroll.translatesAutoresizingMaskIntoConstraints = NO;
	[self.view addSubview:scroll];
	[scroll addSubview:stack];
	UILayoutGuide *safe = self.view.safeAreaLayoutGuide;
	[NSLayoutConstraint activateConstraints:@[
		[scroll.topAnchor constraintEqualToAnchor:safe.topAnchor],
		[scroll.bottomAnchor constraintEqualToAnchor:safe.bottomAnchor],
		[scroll.leadingAnchor constraintEqualToAnchor:safe.leadingAnchor constant:20],
		[scroll.trailingAnchor constraintEqualToAnchor:safe.trailingAnchor constant:-20],
		[stack.topAnchor constraintEqualToAnchor:scroll.topAnchor constant:20],
		[stack.bottomAnchor constraintEqualToAnchor:scroll.bottomAnchor constant:-20],
		[stack.leadingAnchor constraintEqualToAnchor:scroll.leadingAnchor],
		[stack.widthAnchor constraintEqualToAnchor:scroll.widthAnchor],
	]];

	UILabel *title = [UILabel new];
	title.text = @"MODS";
	title.font = zhFont(@"zh_display.ttf", 34, [UIFont boldSystemFontOfSize:34]);
	[stack addArrangedSubview:title];
	NSArray *packs = publishedPacks();
	BOOL arabic = [NSLocale.preferredLanguages.firstObject hasPrefix:@"ar"];
	NSSet *on = enabledPacks();
	UILabel *help = [UILabel new];
	help.numberOfLines = 0;
	help.text = packs.count == 0 ? @"No mods have been published yet."
		: @"Turn a pack on to download it, off to remove its files. Changes apply the next time you open the game.";
	[stack addArrangedSubview:help];
	for (NSDictionary *pack in packs) {
		UIStackView *row = [UIStackView new];
		row.axis = UILayoutConstraintAxisHorizontal;
		row.alignment = UIStackViewAlignmentCenter;
		row.spacing = 12;
		UILabel *label = [UILabel new];
		label.numberOfLines = 0;
		NSString *lang = arabic ? @"ar" : @"en";
		NSString *name = [pack[@"title"] isKindOfClass:NSDictionary.class] ? pack[@"title"][lang] : pack[@"id"];
		NSString *about = [pack[@"description"] isKindOfClass:NSDictionary.class] ? pack[@"description"][lang] : @"";
		BOOL gameplay = [pack[@"gameplay"] boolValue];
		label.text = [NSString stringWithFormat:@"%@\n%@\n%lld MB%@", name ?: pack[@"id"], about ?: @"",
			MAX(1LL, [pack[@"size"] longLongValue] >> 20),
			gameplay ? @" · Changes gameplay: online, every player needs the same" : @""];
		UISwitch *sw = [UISwitch new];
		sw.on = [on containsObject:pack[@"id"]];
		_switches[pack[@"id"]] = sw;
		[row addArrangedSubview:label];
		[row addArrangedSubview:sw];
		[stack addArrangedSubview:row];
	}
	UIButton *apply = [UIButton buttonWithType:UIButtonTypeSystem];
	[apply setTitle:@"APPLY" forState:UIControlStateNormal];
	[apply addTarget:self action:@selector(onApply) forControlEvents:UIControlEventTouchUpInside];
	UIButton *back = [UIButton buttonWithType:UIButtonTypeSystem];
	[back setTitle:@"BACK" forState:UIControlStateNormal];
	[back addTarget:self action:@selector(onBack) forControlEvents:UIControlEventTouchUpInside];
	[stack addArrangedSubview:apply];
	[stack addArrangedSubview:back];
}

- (void)onApply
{
	NSMutableArray *ids = [NSMutableArray array];
	[_switches enumerateKeysAndObjectsUsingBlock:^(NSString *packId, UISwitch *sw, BOOL *stop) {
		if (sw.on) {
			[ids addObject:packId];
		}
	}];
	BOOL changed = ![[NSSet setWithArray:ids] isEqualToSet:enabledPacks()];
	[NSUserDefaults.standardUserDefaults setObject:ids forKey:kPacksDefaultsKey];
	if (!changed) {
		[self onBack];
		return;
	}
	UIAlertController *done = [UIAlertController alertControllerWithTitle:@"Mods saved"
		message:@"Close the game and open it again: the packs you turned on download, and the files of those you turned off are removed."
		preferredStyle:UIAlertControllerStyleAlert];
	[done addAction:[UIAlertAction actionWithTitle:@"OK" style:UIAlertActionStyleDefault handler:^(UIAlertAction *a) {
		[self onBack];
	}]];
	[self presentViewController:done animated:YES completion:nil];
}

- (void)onBack
{
	[self dismissViewControllerAnimated:YES completion:nil];
}

@end

static void iosOpenMods(void)
{
	dispatch_async(dispatch_get_main_queue(), ^{
		UIViewController *host = [ZHSupport gameWindow].rootViewController;
		ZHModsController *mods = [ZHModsController new];
		mods.modalPresentationStyle = UIModalPresentationFullScreen;
		[host presentViewController:mods animated:YES completion:nil];
	});
}

static bool iosModsSummary(char *summary, int size)
{
	NSArray *packs = publishedPacks();
	if (packs.count == 0) {
		return false;
	}
	NSArray *installed = readState()[@"packs"];
	snprintf(summary, size, "%lu of %lu on", (unsigned long)([installed isKindOfClass:NSArray.class] ? installed.count : 0),
		(unsigned long)packs.count);
	return true;
}

static bool iosGameplayMods(void)
{
	NSArray *installed = readState()[@"packs"];
	if (![installed isKindOfClass:NSArray.class]) {
		return false;
	}
	for (NSDictionary *pack in publishedPacks()) {
		if ([pack[@"gameplay"] boolValue] && [installed containsObject:pack[@"id"]]) {
			return true;
		}
	}
	return false;
}

// ---------------------------------------------------------------- GeneralsOnline from the game

// GeneralsX @feature ZH Commander 11/10/2026 What the Android app does through toasts, the
// GeneralsOnline account screen and GameSignIn, for the game's Online button and its ZH COMMANDER
// and lobby screens (ZHCommander.h: onlineAccount, onlineSignIn, onlineSignOut,
// onlineCreateAccount, openCrossPlay, openMoreSettings).

// The view controller on top of the game's window, to present over.
static UIViewController *topController(void)
{
	UIViewController *top = [ZHSupport gameWindow].rootViewController;
	while (top.presentedViewController != nil && !top.presentedViewController.isBeingDismissed) {
		top = top.presentedViewController;
	}
	return top;
}

// A message over the game (Android shows these as toasts). Main thread.
static void showNotice(NSString *title, NSString *message)
{
	UIViewController *host = topController();
	if (host == nil) {
		return;
	}
	UIAlertController *alert = [UIAlertController alertControllerWithTitle:title message:message
		preferredStyle:UIAlertControllerStyleAlert];
	[alert addAction:[UIAlertAction actionWithTitle:@"OK" style:UIAlertActionStyleDefault handler:nil]];
	[host presentViewController:alert animated:YES completion:nil];
}

static void showNoticeLater(NSString *title, NSString *message)
{
	dispatch_async(dispatch_get_main_queue(), ^{ showNotice(title, message); });
}

static BOOL s_signInRunning;

// The reference client's sign-in (OnlineServices_Auth.cpp, DoFullLoginFlow), as GameSignIn.java:
// a login code from the server, the browser with it, then CheckLogin every second until the
// player finishes there (about three minutes at most). The player comes back to the game; the
// wait runs in the background and a message says how it ended.
static void startSignIn(void)
{
	if (s_signInRunning) {
		return;
	}
	s_signInRunning = YES;
	dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
		NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:[kGoApiBase stringByAppendingString:@"LoginCode"]]];
		req.timeoutInterval = 10;
		NSInteger status = 0;
		NSData *data = syncRequest(req, &status);
		NSDictionary *json = data != nil && status >= 200 && status < 300
			? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
		NSString *code = [json isKindOfClass:NSDictionary.class] && [json[@"success"] boolValue]
			? [json[@"login_code"] description] : nil;
		if (code.length == 0) {
			s_signInRunning = NO;
			showNoticeLater(@"GeneralsOnline", @"Could not reach GeneralsOnline. Check the connection and press Online again.");
			return;
		}
		dispatch_async(dispatch_get_main_queue(), ^{
			NSString *escaped = [code stringByAddingPercentEncodingWithAllowedCharacters:NSCharacterSet.URLQueryAllowedCharacterSet];
			NSURL *url = [NSURL URLWithString:[NSString stringWithFormat:kGoLoginURLFormat, escaped]];
			[UIApplication.sharedApplication openURL:url options:@{} completionHandler:nil];
		});
		NSMutableDictionary *body = [goIdentityBody() mutableCopy];
		body[@"code"] = code;
		body[@"client_id"] = kGoClientId;
		for (int attempt = 0; attempt < 180; ++attempt) {
			sleep(1);
			NSInteger httpStatus = 0;
			NSDictionary *result = goPost(@"CheckLogin", body, nil, &httpStatus);
			if (result == nil) {
				continue;  // a dropped request: the next one may get through
			}
			if ([result[@"result"] integerValue] == 1) {
				writeSessionMarker(result);
				s_signInRunning = NO;
				showNoticeLater(@"GeneralsOnline", [NSString stringWithFormat:
					@"Signed in to GeneralsOnline as %@. Press Online to play.", [result[@"display_name"] description] ?: @""]);
				return;
			}
			if (httpStatus == 423) {
				s_signInRunning = NO;
				showNoticeLater(@"GeneralsOnline", @"This GeneralsOnline account is banned.");
				return;
			}
			// Anything else means "not yet": the player is still in the browser.
		}
		s_signInRunning = NO;
		showNoticeLater(@"GeneralsOnline", @"The sign-in did not finish. Press Online to try again.");
	});
}

// Forgets this device's session (local only, as on Android).
static void signOut(void)
{
	[NSFileManager.defaultManager removeItemAtPath:sessionMarkerPath() error:nil];
}

// The ONLINE screen: who this device is signed in as, "Play with PC players" and the
// GeneralsOnline data (GeneralsOnlineActivity's card). In the mods screen's sand look.
@interface ZHOnlineController : UIViewController
@end

@implementation ZHOnlineController {
	UILabel *_account;
	UIButton *_accountButton;
	UISwitch *_crossPlay;
	UILabel *_dataState;
	UIButton *_getData;
	NSTimer *_refresh;
}

static BOOL s_patchInstallRunning;

- (UILabel *)text:(UIStackView *)stack size:(CGFloat)size bold:(BOOL)bold
{
	UILabel *label = [UILabel new];
	label.numberOfLines = 0;
	label.textColor = zhColor(0x1D1F14);
	label.font = bold ? zhFont(@"zh_display.ttf", size, [UIFont boldSystemFontOfSize:size])
					  : zhFont(@"zh_body.ttf", size, [UIFont systemFontOfSize:size]);
	[stack addArrangedSubview:label];
	return label;
}

- (UIButton *)button:(UIStackView *)stack title:(NSString *)title action:(SEL)action
{
	UIButton *button = [UIButton buttonWithType:UIButtonTypeSystem];
	button.backgroundColor = zhColor(0xE4D8B3);
	[button setTitleColor:zhColor(0x1D1F14) forState:UIControlStateNormal];
	button.titleLabel.font = zhFont(@"zh_display.ttf", 19, [UIFont boldSystemFontOfSize:17]);
	button.layer.cornerRadius = 3;
	button.layer.borderWidth = 2;
	button.layer.borderColor = zhColor(0x4A5228).CGColor;
	[button setTitle:title forState:UIControlStateNormal];
	[button addTarget:self action:action forControlEvents:UIControlEventTouchUpInside];
	[button.heightAnchor constraintEqualToConstant:44].active = YES;
	[stack addArrangedSubview:button];
	return button;
}

- (void)viewDidLoad
{
	[super viewDidLoad];
	self.view.backgroundColor = zhColor(0xD2C192);
	UIScrollView *scroll = [UIScrollView new];
	scroll.translatesAutoresizingMaskIntoConstraints = NO;
	[self.view addSubview:scroll];
	UIStackView *stack = [UIStackView new];
	stack.axis = UILayoutConstraintAxisVertical;
	stack.spacing = 12;
	stack.translatesAutoresizingMaskIntoConstraints = NO;
	[scroll addSubview:stack];
	UILayoutGuide *safe = self.view.safeAreaLayoutGuide;
	[NSLayoutConstraint activateConstraints:@[
		[scroll.topAnchor constraintEqualToAnchor:safe.topAnchor],
		[scroll.bottomAnchor constraintEqualToAnchor:safe.bottomAnchor],
		[scroll.leadingAnchor constraintEqualToAnchor:safe.leadingAnchor constant:24],
		[scroll.trailingAnchor constraintEqualToAnchor:safe.trailingAnchor constant:-24],
		[stack.topAnchor constraintEqualToAnchor:scroll.topAnchor constant:20],
		[stack.bottomAnchor constraintEqualToAnchor:scroll.bottomAnchor constant:-20],
		[stack.leadingAnchor constraintEqualToAnchor:scroll.leadingAnchor],
		[stack.widthAnchor constraintEqualToAnchor:scroll.widthAnchor],
	]];

	[self text:stack size:34 bold:YES].text = @"ONLINE";
	[self text:stack size:20 bold:YES].text = @"GENERALSONLINE ACCOUNT";
	_account = [self text:stack size:16 bold:NO];
	_accountButton = [self button:stack title:@"SIGN IN" action:@selector(onAccount)];
	[self text:stack size:14 bold:NO].text = @"No account yet? The easiest is a free GameReplays account: create it, "
		"come back, press Sign in and choose GameReplays.";
	[self button:stack title:@"CREATE A GAMEREPLAYS ACCOUNT" action:@selector(onCreateAccount)];

	[self text:stack size:20 bold:YES].text = @"PLAY WITH PC PLAYERS";
	UIStackView *row = [UIStackView new];
	row.axis = UILayoutConstraintAxisHorizontal;
	row.alignment = UIStackViewAlignmentCenter;
	row.spacing = 12;
	UILabel *explain = [UILabel new];
	explain.numberOfLines = 0;
	explain.textColor = zhColor(0x1D1F14);
	explain.font = zhFont(@"zh_body.ttf", 15, [UIFont systemFontOfSize:15]);
	explain.text = @"On: the GeneralsOnline data and the PC client's checksum, so you play with PC players and "
		"phones alike (this game always runs at 60 Hz, as the PC does). Off: retail data, with phones that "
		"turned it off too. Applies the next time you open the game.";
	_crossPlay = [UISwitch new];
	_crossPlay.onTintColor = zhColor(0xC2461B);
	[_crossPlay addTarget:self action:@selector(onCrossPlay) forControlEvents:UIControlEventValueChanged];
	[row addArrangedSubview:explain];
	[row addArrangedSubview:_crossPlay];
	[stack addArrangedSubview:row];
	_dataState = [self text:stack size:15 bold:NO];
	_getData = [self button:stack title:@"GET THE GENERALSONLINE DATA" action:@selector(onGetData)];

	[stack setCustomSpacing:24 afterView:_getData];
	[self button:stack title:@"BACK" action:@selector(onBack)];
	[self refresh];
}

- (void)viewDidAppear:(BOOL)animated
{
	[super viewDidAppear:animated];
	// The sign-in finishes in the browser and comes back here.
	_refresh = [NSTimer scheduledTimerWithTimeInterval:1 target:self selector:@selector(refresh) userInfo:nil repeats:YES];
}

- (void)viewWillDisappear:(BOOL)animated
{
	[super viewWillDisappear:animated];
	[_refresh invalidate];
	_refresh = nil;
}

- (void)refresh
{
	NSString *name = goSignedInName();
	_account.text = name != nil ? [NSString stringWithFormat:@"Signed in as %@", name]
		: (s_signInRunning ? @"Signing in: finish in the browser, then come back." : @"Not signed in");
	[_accountButton setTitle:(name != nil ? @"SIGN OUT" : @"SIGN IN") forState:UIControlStateNormal];
	_crossPlay.on = crossPlayWanted();
	NSString *installed = patchInstalledVersion();
	NSString *state = installed != nil ? [NSString stringWithFormat:@"GeneralsOnline data: version %@", installed]
		: @"GeneralsOnline data: not installed";
	if (installed != nil && !hasPcExeCrcSeed()) {
		state = [state stringByAppendingString:@" (no PC checksum in this package)"];
	}
	if (s_patchInstallRunning) {
		state = @"GeneralsOnline data: downloading…";
	}
	_dataState.text = state;
	_getData.hidden = !crossPlayWanted();
	_getData.enabled = !s_patchInstallRunning;
	[_getData setTitle:(installed != nil ? @"UPDATE THE GENERALSONLINE DATA" : @"GET THE GENERALSONLINE DATA")
		forState:UIControlStateNormal];
}

- (void)onAccount
{
	if (goSignedInName() == nil) {
		startSignIn();
		[self refresh];
		return;
	}
	UIAlertController *ask = [UIAlertController alertControllerWithTitle:@"GeneralsOnline"
		message:@"Sign out of GeneralsOnline on this device?" preferredStyle:UIAlertControllerStyleAlert];
	[ask addAction:[UIAlertAction actionWithTitle:@"Sign out" style:UIAlertActionStyleDestructive handler:^(UIAlertAction *a) {
		signOut();
		[self refresh];
	}]];
	[ask addAction:[UIAlertAction actionWithTitle:@"Cancel" style:UIAlertActionStyleCancel handler:nil]];
	[self presentViewController:ask animated:YES completion:nil];
}

- (void)onCreateAccount
{
	[UIApplication.sharedApplication openURL:[NSURL URLWithString:kGameReplaysSignUpURL] options:@{} completionHandler:nil];
}

- (void)onCrossPlay
{
	setCrossPlay(_crossPlay.on);
	// On, and the data missing: fetch it now rather than at the next start.
	if (_crossPlay.on && patchInstalledVersion() == nil) {
		[self onGetData];
	}
	[self refresh];
}

// Fetched in the background, written beside what the running game has open and moved over it;
// the game mounts it the next time it starts.
- (void)onGetData
{
	if (s_patchInstallRunning) {
		return;
	}
	s_patchInstallRunning = YES;
	[self refresh];
	dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
		NSString *failure = installCommunityPatch([ZHPatchProgress new]);
		dispatch_async(dispatch_get_main_queue(), ^{
			s_patchInstallRunning = NO;
			showNotice(@"GeneralsOnline data", failure != nil ? failure
				: @"Ready. Close the game and open it again to play with it.");
		});
	});
}

- (void)onBack
{
	[self dismissViewControllerAnimated:YES completion:nil];
}

- (UIInterfaceOrientationMask)supportedInterfaceOrientations
{
	return UIInterfaceOrientationMaskLandscape;
}

@end

static bool iosOnlineAccount(char *name, int size)
{
	NSString *signedIn = goSignedInName();
	if (signedIn == nil) {
		return false;
	}
	snprintf(name, size, "%s", signedIn.UTF8String ?: "");
	return true;
}

static void iosOnlineSignIn(void)
{
	dispatch_async(dispatch_get_main_queue(), ^{ startSignIn(); });
}

static void iosOnlineSignOut(void)
{
	signOut();
}

static void iosOnlineCreateAccount(void)
{
	dispatch_async(dispatch_get_main_queue(), ^{
		[UIApplication.sharedApplication openURL:[NSURL URLWithString:kGameReplaysSignUpURL] options:@{} completionHandler:nil];
	});
}

static void iosOpenOnline(void)
{
	dispatch_async(dispatch_get_main_queue(), ^{
		UIViewController *host = topController();
		ZHOnlineController *online = [ZHOnlineController new];
		online.modalPresentationStyle = UIModalPresentationFullScreen;
		[host presentViewController:online animated:YES completion:nil];
	});
}

static bool iosReleaseNotes(const char *version, char *title, int titleSize, char *body, int bodySize)
{
	return s_latestNotes != nil && [s_latestNotes[@"version"] isEqual:@(version)]
		&& notesInto(s_latestNotes, title, titleSize, body, bodySize);
}

static void checkForUpdatesInBackground(void)
{
	NSMutableURLRequest *source = [NSMutableURLRequest requestWithURL:
		[NSURL URLWithString:[kSite stringByAppendingString:@"/altstore.json"]]];
	source.cachePolicy = NSURLRequestReloadIgnoringLocalCacheData;
	[[NSURLSession.sharedSession dataTaskWithRequest:source
		completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
			NSDictionary *json = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
			NSArray *apps = [json isKindOfClass:NSDictionary.class] ? json[@"apps"] : nil;
			NSDictionary *app = [apps isKindOfClass:NSArray.class] ? apps.firstObject : nil;
			NSArray *versions = [app isKindOfClass:NSDictionary.class] ? app[@"versions"] : nil;
			NSDictionary *latest = [versions isKindOfClass:NSArray.class] && versions.count > 0 ? versions.firstObject : nil;
			if ([latest isKindOfClass:NSDictionary.class] && [latest[@"version"] isKindOfClass:NSString.class]) {
				dispatch_async(dispatch_get_main_queue(), ^{ s_latestApp = latest; });
				// Its release notes, for the update dialog (iosReleaseNotes).
				NSString *notesURL = [NSString stringWithFormat:@"%@/api/notes/%@", kSite, latest[@"version"]];
				[[NSURLSession.sharedSession dataTaskWithURL:[NSURL URLWithString:notesURL]
					completionHandler:^(NSData *notesData, NSURLResponse *r, NSError *e) {
						NSDictionary *notes = notesData ? [NSJSONSerialization JSONObjectWithData:notesData options:0 error:nil] : nil;
						if ([notes isKindOfClass:NSDictionary.class]) {
							dispatch_async(dispatch_get_main_queue(), ^{ s_latestNotes = notes; });
						}
					}] resume];
			}
		}] resume];

	NSString *license = storedLicense();
	if (license == nil) {
		return;
	}
	NSMutableURLRequest *manifest = [NSMutableURLRequest requestWithURL:
		[NSURL URLWithString:[kSite stringByAppendingString:@"/api/data/manifest"]]];
	[manifest setValue:license forHTTPHeaderField:@"X-ZH-License"];
	[[NSURLSession.sharedSession dataTaskWithRequest:manifest
		completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
			NSDictionary *json = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
			NSString *version = [json isKindOfClass:NSDictionary.class] ? json[@"version"] : nil;
			if ([version isKindOfClass:NSString.class]) {
				dispatch_async(dispatch_get_main_queue(), ^{ s_latestData = version; });
			}
		}] resume];
}

static const char *iosAppVersion(void)
{
	static char s_version[32];
	if (s_version[0] == '\0') {
		NSString *mine = NSBundle.mainBundle.infoDictionary[@"CFBundleShortVersionString"];
		snprintf(s_version, sizeof(s_version), "%s", mine.UTF8String ?: "?");
	}
	return s_version;
}

// Releases are required on iOS (see updateRequired above): the next start will not go past the gate.
static bool iosAppUpdateOffer(char *version, int size, bool *mandatory)
{
	if (s_latestApp == nil || !isNewerRelease(s_latestApp)) {
		return false;
	}
	snprintf(version, size, "%s", [s_latestApp[@"version"] UTF8String]);
	*mandatory = true;
	return true;
}

static void iosStartAppUpdate(void)
{
	// SideStore first, then AltStore; whichever the player installed answers.
	UIApplication *app = UIApplication.sharedApplication;
	[app openURL:[NSURL URLWithString:@"sidestore://"] options:@{} completionHandler:^(BOOL opened) {
		if (!opened) {
			[app openURL:[NSURL URLWithString:@"altstore://"] options:@{} completionHandler:nil];
		}
	}];
}

// Downloaded by the gate on the next start; iOS apps cannot restart themselves, so there is no
// startDataUpdate and the game asks the player to close and reopen it.
static bool iosDataUpdateOffer(char *version, int size)
{
	NSString *installed = readState()[@"version"];
	if (s_latestData == nil || installed == nil || [s_latestData isEqual:installed]) {
		return false;
	}
	snprintf(version, size, "%s", s_latestData.UTF8String);
	return true;
}

static void iosShareSupportReport(void)
{
	dispatch_async(dispatch_get_main_queue(), ^{
		[ZHSupport shareReport];
	});
}

extern "C" void ZHIOSInstallHooks(void)
{
	ZHCommander::Hooks &h = ZHCommander::hooks();
	h.appVersion = iosAppVersion;
	h.appUpdateOffer = iosAppUpdateOffer;
	h.startAppUpdate = iosStartAppUpdate;
	h.dataUpdateOffer = iosDataUpdateOffer;
	h.shareSupportReport = iosShareSupportReport;
	h.whatsNew = iosWhatsNew;
	h.releaseNotes = iosReleaseNotes;
	h.openMods = iosOpenMods;
	h.modsSummary = iosModsSummary;
	h.gameplayMods = iosGameplayMods;
	// GeneralsX @feature ZH Commander 11/10/2026 GeneralsOnline and cross-play, as on Android. Left
	// null on purpose: getSetting/setSetting (one engine at 60 Hz and one renderer, so the ZH
	// COMMANDER screen hides the speed, renderer, upscaling and usage-data rows), restart and
	// startDataUpdate (an iOS app cannot start itself again; the menus say to close and reopen),
	// and event (no App Monitor SDK on iOS yet).
	h.onlineAccount = iosOnlineAccount;
	h.onlineSignIn = iosOnlineSignIn;
	h.onlineSignOut = iosOnlineSignOut;
	h.onlineCreateAccount = iosOnlineCreateAccount;
	h.openCrossPlay = iosOpenOnline;
	h.openMoreSettings = iosOpenOnline;
	checkForUpdatesInBackground();
}
