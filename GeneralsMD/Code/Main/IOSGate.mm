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
// Then the window goes away and the engine starts. ZHIOSInstallSupportButton() puts a small
// button over the game's window that shares a support report (device, versions, logs).
//
// Updates of the app itself come through AltStore/SideStore (the site's /altstore.json), so
// there is no in-app updater here.

#import <UIKit/UIKit.h>
#import <Security/Security.h>
#import <CommonCrypto/CommonDigest.h>

static NSString *const kActivateURL = @"https://gzh-license.housam-kak20.workers.dev/v1/activate";
static NSString *const kSite = @"https://zerohour.housamkak.com";
// SubjectPublicKeyInfo (DER, base64) of the license server's signing key -- LicenseGate.PUBLIC_KEY_B64.
static NSString *const kLicensePublicKey =
	@"MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEAhE861NJAUCPcAoeya8LDyNmUux2QKucHdX3ndZ+IjYbkPYVqQU/GXBmn5tsuKg1Wp3b70Xx6aLSbsvJEfjOmg==";
static NSString *const kLicenseKey = @"zh.license";
static NSString *const kStateFile = @".zh-data.json";

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

static NSString *documentsDir(void)
{
	return NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES).firstObject;
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
@interface ZHDownloader : NSObject <NSURLSessionDataDelegate>
@property (nonatomic, strong) NSFileHandle *handle;
@property (nonatomic) long long received;
@property (nonatomic) BOOL done;
@property (nonatomic) BOOL failed;
@property (nonatomic, copy) void (^onBytes)(long long received);
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
	self.received += (long long)data.length;
	if (self.onBytes) {
		long long r = self.received;
		dispatch_async(dispatch_get_main_queue(), ^{ self.onBytes(r); });
	}
}
- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error
{
	if (error != nil) {
		self.failed = YES;
	}
	self.done = YES;
}
@end

// ---------------------------------------------------------------- the gate screen

@interface ZHGateController : UIViewController <UITextFieldDelegate>
@property (nonatomic) BOOL finished;
@end

@implementation ZHGateController {
	BOOL _started;
	UILabel *_title;
	UILabel *_body;
	UITextField *_field;
	UIButton *_primary;
	UIButton *_secondary;
	UILabel *_status;
	UIProgressView *_progress;
	NSString *_dataVersion;
	NSArray<ZHDataFile *> *_manifestFiles;
	NSArray<ZHDataFile *> *_todo;
}

- (void)viewDidLoad
{
	[super viewDidLoad];
	UIColor *sand = [UIColor colorWithRed:0.82 green:0.76 blue:0.57 alpha:1];
	UIColor *ink = [UIColor colorWithRed:0.11 green:0.12 blue:0.08 alpha:1];
	UIColor *signal = [UIColor colorWithRed:0.76 green:0.27 blue:0.11 alpha:1];
	self.view.backgroundColor = sand;

	_title = [UILabel new];
	_title.font = [UIFont boldSystemFontOfSize:26];
	_title.textColor = ink;
	_body = [UILabel new];
	_body.numberOfLines = 0;
	_body.textColor = ink;
	_body.font = [UIFont systemFontOfSize:16];
	_field = [UITextField new];
	_field.borderStyle = UITextBorderStyleRoundedRect;
	_field.placeholder = @"GZH-XXXX-XXXX-XXXX";
	_field.autocapitalizationType = UITextAutocapitalizationTypeAllCharacters;
	_field.autocorrectionType = UITextAutocorrectionTypeNo;
	_field.returnKeyType = UIReturnKeyGo;
	_field.delegate = self;
	_primary = [UIButton buttonWithType:UIButtonTypeSystem];
	_primary.backgroundColor = signal;
	[_primary setTitleColor:UIColor.whiteColor forState:UIControlStateNormal];
	_primary.titleLabel.font = [UIFont boldSystemFontOfSize:18];
	_primary.layer.cornerRadius = 8;
	[_primary addTarget:self action:@selector(onPrimary) forControlEvents:UIControlEventTouchUpInside];
	_secondary = [UIButton buttonWithType:UIButtonTypeSystem];
	[_secondary setTitleColor:ink forState:UIControlStateNormal];
	[_secondary addTarget:self action:@selector(onSecondary) forControlEvents:UIControlEventTouchUpInside];
	_status = [UILabel new];
	_status.numberOfLines = 0;
	_status.textColor = ink;
	_status.font = [UIFont systemFontOfSize:14];
	_progress = [[UIProgressView alloc] initWithProgressViewStyle:UIProgressViewStyleDefault];
	_progress.progressTintColor = signal;

	UIStackView *stack = [[UIStackView alloc] initWithArrangedSubviews:@[_title, _body, _field, _progress, _primary, _secondary, _status]];
	stack.axis = UILayoutConstraintAxisVertical;
	stack.spacing = 14;
	stack.translatesAutoresizingMaskIntoConstraints = NO;
	[self.view addSubview:stack];
	UILayoutGuide *g = self.view.safeAreaLayoutGuide;
	[NSLayoutConstraint activateConstraints:@[
		[stack.centerXAnchor constraintEqualToAnchor:g.centerXAnchor],
		[stack.centerYAnchor constraintEqualToAnchor:g.centerYAnchor],
		[stack.widthAnchor constraintLessThanOrEqualToConstant:520],
		[stack.leadingAnchor constraintGreaterThanOrEqualToAnchor:g.leadingAnchor constant:24],
		[stack.trailingAnchor constraintLessThanOrEqualToAnchor:g.trailingAnchor constant:-24],
		[_primary.heightAnchor constraintEqualToConstant:50],
	]];
	NSLayoutConstraint *wide = [stack.widthAnchor constraintEqualToConstant:520];
	wide.priority = UILayoutPriorityDefaultHigh;
	wide.active = YES;

	[self show:@"ZH Commander" body:@"Checking…" primary:nil secondary:nil field:NO];
}

// The checks wait on the network, and the screen is only on display once this view has
// appeared: started any earlier (viewDidLoad), they left the player looking at a black window.
- (void)viewDidAppear:(BOOL)animated
{
	[super viewDidAppear:animated];
	if (_started) {
		return;
	}
	_started = YES;
	dispatch_async(dispatch_get_main_queue(), ^{
		[self step];
	});
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
	NSString *latest = [versions isKindOfClass:NSArray.class] && versions.count > 0 ? versions.firstObject[@"version"] : nil;
	NSString *mine = NSBundle.mainBundle.infoDictionary[@"CFBundleShortVersionString"];
	if (![latest isKindOfClass:NSString.class] || mine == nil
		|| [mine compare:latest options:NSNumericSearch] != NSOrderedAscending) {
		return NO;
	}
	[self show:@"Update required"
		  body:[NSString stringWithFormat:@"ZH Commander %@ is available and required to play (you have %@). Open SideStore or AltStore and update ZH Commander, then open the game again.", latest, mine]
	   primary:@"Open SideStore" secondary:@"Check again" field:NO];
	_primary.tag = 4;
	_secondary.tag = 4;
	return YES;
}

- (void)show:(NSString *)title body:(NSString *)body primary:(NSString *)primary secondary:(NSString *)secondary field:(BOOL)field
{
	_title.text = title;
	_body.text = body;
	_primary.tag = 0;  // each screen sets the actions it wants after calling show
	_secondary.tag = 0;
	_field.hidden = !field;
	_progress.hidden = YES;
	_primary.hidden = primary == nil;
	[_primary setTitle:primary forState:UIControlStateNormal];
	_primary.enabled = YES;
	_secondary.hidden = secondary == nil;
	[_secondary setTitle:secondary forState:UIControlStateNormal];
	_status.text = @"";
}

- (void)showActivation
{
	[self show:@"Activate this device"
		  body:@"Enter the activation key you were given. It works on one device only; this device is checked once, online, and then plays offline."
	   primary:@"Activate" secondary:nil field:YES];
	_primary.tag = 1;
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
	self.finished = YES;  // "Later" on a data update: go on to the game
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
	[self show:@"Game data" body:@"Checking for game data…" primary:nil secondary:nil field:NO];
	NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:
		[NSURL URLWithString:[kSite stringByAppendingString:@"/api/data/manifest"]]];
	[req setValue:storedLicense() forHTTPHeaderField:@"X-ZH-License"];
	req.timeoutInterval = 20;
	NSInteger status = 0;
	NSData *data = runRequest(req, &status);
	NSDictionary *json = data && status == 200 ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
	if (![json isKindOfClass:NSDictionary.class]) {
		if (haveGameData()) {
			self.finished = YES;  // offline or nothing published: play what is installed
			return;
		}
		[self show:@"Game data"
			  body:(data == nil ? @"Couldn't reach the download server. Check your internet connection."
								: @"No game data has been published yet. Try again later.")
		   primary:@"Try again" secondary:nil field:NO];
		_primary.tag = 3;
		return;
	}
	NSMutableArray *files = [NSMutableArray array];
	for (NSDictionary *f in json[@"files"]) {
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
		[self show:@"Game data" body:@"Resuming the download…" primary:@"Download" secondary:nil field:NO];
		_primary.tag = 2;
		[self download];
		return;
	}
	if (todo.count == 0) {
		self.finished = YES;
		return;
	}
	BOOL update = haveGameData();
	NSString *body = update
		? [NSString stringWithFormat:@"New game data is available (version %@, about %lld MB). Only the files that changed are downloaded.", _dataVersion, MAX(1LL, bytes >> 20)]
		: [NSString stringWithFormat:@"ZH Commander needs its game data to play (version %@, about %lld MB). It downloads once, straight into the game; use Wi-Fi if you can.", _dataVersion, MAX(1LL, bytes >> 20)];
	[self show:@"Game data" body:body primary:@"Download" secondary:(update ? @"Later" : nil) field:NO];
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
	_primary.enabled = NO;
	_secondary.hidden = YES;
	_progress.hidden = NO;
	long long total = 0;
	for (ZHDataFile *e in _todo) {
		total += e.size;
	}
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
				while ([until timeIntervalSinceNow] > 0) {
					[NSRunLoop.currentRunLoop runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.1]];
				}
			}
			fetched = [self fetch:e to:part license:license before:doneBytes total:total];
		}
		if (!fetched) {
			[UIApplication.sharedApplication setIdleTimerDisabled:NO];
			_status.text = @"The download stopped. Tap Download to continue where it left off.";
			_primary.enabled = YES;
			return;
		}
		[fm removeItemAtPath:target error:nil];
		if (![fm moveItemAtPath:part toPath:target error:nil]) {
			[UIApplication.sharedApplication setIdleTimerDisabled:NO];
			_status.text = @"Couldn't save the game data. Free up some space and try again.";
			_primary.enabled = YES;
			return;
		}
		finished[e.path] = e.sha256;
		writeProgress(_dataVersion, finished);
		doneBytes += e.size;
	}
	[UIApplication.sharedApplication setIdleTimerDisabled:NO];
	// Files the previous data version had and this one does not.
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
	NSData *json = [NSJSONSerialization dataWithJSONObject:@{@"version": _dataVersion ?: @"", @"files": state} options:0 error:nil];
	[json writeToFile:[docs stringByAppendingPathComponent:kStateFile] atomically:YES];
	[fm removeItemAtPath:progressPath() error:nil];
	self.finished = YES;
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
		UIProgressView *bar = _progress;
		UILabel *status = _status;
		dl.onBytes = ^(long long received) {
			long long done = before + received;
			bar.progress = total > 0 ? (float)((double)done / (double)total) : 0;
			status.text = [NSString stringWithFormat:@"%lld / %lld MB", done >> 20, total >> 20];
		};
		NSURLSession *session = [NSURLSession sessionWithConfiguration:NSURLSessionConfiguration.defaultSessionConfiguration
			delegate:dl delegateQueue:nil];
		[[session dataTaskWithRequest:req] resume];
		while (!dl.done) {
			[NSRunLoop.currentRunLoop runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
		}
		[session finishTasksAndInvalidate];
		[dl.handle closeFile];
		if (dl.failed) {
			return NO;
		}
	}
	long long size = (long long)[[fm attributesOfItemAtPath:part error:nil] fileSize];
	if (size != e.size || ![sha256OfFile(part) isEqual:e.sha256]) {
		[fm removeItemAtPath:part error:nil];
		return NO;
	}
	return YES;
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

// ---------------------------------------------------------------- support button

@interface ZHSupport : NSObject
+ (void)attach;
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

+ (void)attach
{
	UIWindow *window = [self gameWindow];
	if (window == nil) {
		// The engine creates its window a moment after the gate closes.
		dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(1 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
			[ZHSupport attach];
		});
		return;
	}
	UIButton *button = [UIButton buttonWithType:UIButtonTypeSystem];
	[button setImage:[UIImage systemImageNamed:@"wrench.and.screwdriver"] forState:UIControlStateNormal];
	button.tintColor = UIColor.whiteColor;
	button.backgroundColor = [UIColor colorWithWhite:0 alpha:0.5];
	button.layer.cornerRadius = 18;
	button.alpha = 0.7;
	button.accessibilityLabel = @"Support";
	button.translatesAutoresizingMaskIntoConstraints = NO;
	[button addTarget:self action:@selector(onTap:) forControlEvents:UIControlEventTouchUpInside];
	UIView *host = window.rootViewController.view;
	[host addSubview:button];
	[NSLayoutConstraint activateConstraints:@[
		[button.widthAnchor constraintEqualToConstant:36],
		[button.heightAnchor constraintEqualToConstant:36],
		[button.topAnchor constraintEqualToAnchor:host.safeAreaLayoutGuide.topAnchor constant:10],
		[button.trailingAnchor constraintEqualToAnchor:host.safeAreaLayoutGuide.trailingAnchor constant:-10],
	]];
}

+ (void)onTap:(UIButton *)button
{
	UIViewController *host = [self gameWindow].rootViewController;
	UIAlertController *sheet = [UIAlertController alertControllerWithTitle:@"Support"
		message:[self versionLine] preferredStyle:UIAlertControllerStyleActionSheet];
	[sheet addAction:[UIAlertAction actionWithTitle:@"Create and send report" style:UIAlertActionStyleDefault
		handler:^(UIAlertAction *a) { [self shareReportFrom:button]; }]];
	[sheet addAction:[UIAlertAction actionWithTitle:@"Back to the game" style:UIAlertActionStyleCancel handler:nil]];
	sheet.popoverPresentationController.sourceView = button;  // iPad presents sheets as popovers
	sheet.popoverPresentationController.sourceRect = button.bounds;
	[host presentViewController:sheet animated:YES completion:nil];
}

+ (NSString *)versionLine
{
	NSDictionary *info = NSBundle.mainBundle.infoDictionary;
	return [NSString stringWithFormat:@"ZH Commander %@ (%@)\nGame data %@",
		info[@"CFBundleShortVersionString"], info[@"CFBundleVersion"], readState()[@"version"] ?: @"none"];
}

+ (void)shareReportFrom:(UIButton *)button
{
	NSMutableString *report = [NSMutableString stringWithString:@"ZH Commander support report\n\n"];
	[report appendFormat:@"%@\n", [self versionLine]];
	UIDevice *d = UIDevice.currentDevice;
	[report appendFormat:@"Device: %@, %@ %@\n", d.model, d.systemName, d.systemVersion];
	[report appendFormat:@"Activated: %@ (device %@)\n", storedLicense() ? @"yes" : @"no", [deviceHash() substringToIndex:12]];
	NSNumber *free = [[NSFileManager.defaultManager attributesOfFileSystemForPath:documentsDir() error:nil]
		objectForKey:NSFileSystemFreeSize];
	[report appendFormat:@"Storage free: %lld MB\n\nGame data files:\n", free.longLongValue >> 20];
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
	UIActivityViewController *share = [[UIActivityViewController alloc] initWithActivityItems:items applicationActivities:nil];
	share.popoverPresentationController.sourceView = button;
	share.popoverPresentationController.sourceRect = button.bounds;
	[[self gameWindow].rootViewController presentViewController:share animated:YES completion:nil];
}

@end

extern "C" void ZHIOSInstallSupportButton(void)
{
	dispatch_async(dispatch_get_main_queue(), ^{
		[ZHSupport attach];
	});
}
