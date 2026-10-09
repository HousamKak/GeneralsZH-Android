/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @feature ZH Commander 09/10/2026 The Android half of ZHCommander.h: each hook calls a
// static method of com.generalsx.zerohour.ZHBridge. The class is looked up once, on the thread
// SDL3Main runs on: that thread was started from Java, so FindClass sees the app's class loader
// there (a thread attached from native code would only see the system's). The engine calls the
// hooks from the same thread.

#include <jni.h>
#include <SDL3/SDL.h>
#include <SDL3/SDL_system.h>
#include <cstdio>
#include <cstring>

#include "Common/ZHCommander.h"

static jclass s_bridge = nullptr;

static JNIEnv *bridgeEnv()
{
	return s_bridge != nullptr ? static_cast<JNIEnv *>(SDL_GetAndroidJNIEnv()) : nullptr;
}

// A Java exception left pending would abort the next JNI call; log it and go on.
static void clearException(JNIEnv *env, const char *what)
{
	if (env->ExceptionCheck())
	{
		fprintf(stderr, "WARNING: ZHBridge.%s threw\n", what);
		env->ExceptionDescribe();
		env->ExceptionClear();
	}
}

static void callVoid(const char *name)
{
	JNIEnv *env = bridgeEnv();
	if (env == nullptr)
		return;
	jmethodID m = env->GetStaticMethodID(s_bridge, name, "()V");
	if (m != nullptr)
		env->CallStaticVoidMethod(s_bridge, m);
	clearException(env, name);
}

// Copies a String result into out; false when Java returned null.
static bool callString(const char *name, jstring arg, char *out, int size)
{
	JNIEnv *env = bridgeEnv();
	if (env == nullptr || size <= 0)
		return false;
	out[0] = '\0';
	jmethodID m = env->GetStaticMethodID(s_bridge, name,
		arg != nullptr ? "(Ljava/lang/String;)Ljava/lang/String;" : "()Ljava/lang/String;");
	jstring result = nullptr;
	if (m != nullptr)
		result = static_cast<jstring>(arg != nullptr ? env->CallStaticObjectMethod(s_bridge, m, arg)
			: env->CallStaticObjectMethod(s_bridge, m));
	clearException(env, name);
	if (result == nullptr)
		return false;
	const char *chars = env->GetStringUTFChars(result, nullptr);
	if (chars != nullptr)
	{
		snprintf(out, size, "%s", chars);
		env->ReleaseStringUTFChars(result, chars);
	}
	env->DeleteLocalRef(result);
	return chars != nullptr;
}

static const char *appVersion()
{
	static char s_version[32];
	if (s_version[0] == '\0' && !callString("appVersion", nullptr, s_version, sizeof(s_version)))
		snprintf(s_version, sizeof(s_version), "?");
	return s_version;
}

// ZHBridge.appUpdateOffer() answers "1.5.8", or "!1.5.8" for a required update.
static bool appUpdateOffer(char *version, int size, bool *mandatory)
{
	char raw[64];
	if (!callString("appUpdateOffer", nullptr, raw, sizeof(raw)) || raw[0] == '\0')
		return false;
	*mandatory = raw[0] == '!';
	snprintf(version, size, "%s", *mandatory ? raw + 1 : raw);
	return true;
}

static bool dataUpdateOffer(char *version, int size)
{
	return callString("dataUpdateOffer", nullptr, version, size) && version[0] != '\0';
}

static bool getSetting(const char *key, char *value, int size)
{
	JNIEnv *env = bridgeEnv();
	if (env == nullptr)
		return false;
	jstring jkey = env->NewStringUTF(key);
	const bool found = callString("getSetting", jkey, value, size);
	env->DeleteLocalRef(jkey);
	return found;
}

static void setSetting(const char *key, const char *value)
{
	JNIEnv *env = bridgeEnv();
	if (env == nullptr)
		return;
	jmethodID m = env->GetStaticMethodID(s_bridge, "setSetting", "(Ljava/lang/String;Ljava/lang/String;)V");
	if (m != nullptr)
	{
		jstring jkey = env->NewStringUTF(key);
		jstring jvalue = env->NewStringUTF(value);
		env->CallStaticVoidMethod(s_bridge, m, jkey, jvalue);
		env->DeleteLocalRef(jkey);
		env->DeleteLocalRef(jvalue);
	}
	clearException(env, "setSetting");
}

static void event(const char *name)
{
	JNIEnv *env = bridgeEnv();
	if (env == nullptr)
		return;
	jmethodID m = env->GetStaticMethodID(s_bridge, "event", "(Ljava/lang/String;)V");
	if (m != nullptr)
	{
		jstring jname = env->NewStringUTF(name);
		env->CallStaticVoidMethod(s_bridge, m, jname);
		env->DeleteLocalRef(jname);
	}
	clearException(env, "event");
}

static bool onlineAccount(char *name, int size)
{
	return callString("onlineAccount", nullptr, name, size) && name[0] != '\0';
}

static void onlineSignIn() { callVoid("onlineSignIn"); }
static void onlineSignOut() { callVoid("onlineSignOut"); }
static void startAppUpdate() { callVoid("startAppUpdate"); }
static void startDataUpdate() { callVoid("startDataUpdate"); }
static void shareSupportReport() { callVoid("shareSupportReport"); }
static void openMoreSettings() { callVoid("openMoreSettings"); }
static void restart() { callVoid("restart"); }

void ZHAndroidInstallHooks()
{
	JNIEnv *env = static_cast<JNIEnv *>(SDL_GetAndroidJNIEnv());
	if (env == nullptr)
		return;
	jclass local = env->FindClass("com/generalsx/zerohour/ZHBridge");
	if (local == nullptr)
	{
		clearException(env, "<class>");
		fprintf(stderr, "WARNING: com.generalsx.zerohour.ZHBridge not found; in-game app menus disabled\n");
		return;
	}
	s_bridge = static_cast<jclass>(env->NewGlobalRef(local));
	env->DeleteLocalRef(local);

	ZHCommander::Hooks &h = ZHCommander::hooks();
	h.appVersion = appVersion;
	h.appUpdateOffer = appUpdateOffer;
	h.startAppUpdate = startAppUpdate;
	h.dataUpdateOffer = dataUpdateOffer;
	h.startDataUpdate = startDataUpdate;
	h.shareSupportReport = shareSupportReport;
	h.openMoreSettings = openMoreSettings;
	h.getSetting = getSetting;
	h.setSetting = setSetting;
	h.restart = restart;
	h.event = event;
	h.onlineAccount = onlineAccount;
	h.onlineSignIn = onlineSignIn;
	h.onlineSignOut = onlineSignOut;
	fprintf(stderr, "INFO: ZH Commander %s\n", appVersion());
}
