#pragma once
#include <jni.h>
#include <stdarg.h>
#include <stdio.h>

/*
 * The run's progress, as lines, and this file's one difference from upstream.
 *
 * Upstream resolves the callback once in `JNI_OnLoad`, by name: `FindClass("df/root/IReporter")` and a
 * `GetMethodID` for `report(String)`. That is a second copy of a Java declaration living in C, and when the
 * two disagree the failure is not a missing callback - `FindClass` throws, the `GetMethodID` after it is
 * called with an exception pending, and the JVM aborts inside `System.loadLibrary` on the first tap. A
 * rename in Kotlin is then a crash with a JNI trace that reads like a broken build.
 *
 * So the method is looked up from the object the call is about: the reporter arrives as a parameter on
 * every entry point, so there is nothing to keep in step and nothing that can go stale. The lookup is
 * cached, because a JNI method lookup per log line is a lookup per log line.
 *
 * `UniversalRootContractTest` holds the other half of this boundary - that the native symbol names the
 * Kotlin class it is declared on, and that nothing here looks a class up by name again.
 */
struct Reporter { JNIEnv *env; jobject obj; };

static jmethodID reporter_method(JNIEnv *env, jobject obj) {
    static jmethodID cached = NULL;
    if (cached) return cached;
    jclass cls = (*env)->GetObjectClass(env, obj);
    if (!cls) return NULL;
    cached = (*env)->GetMethodID(env, cls, "report", "(Ljava/lang/String;)V");
    (*env)->DeleteLocalRef(env, cls);
    return cached;
}

static void reportfmt(struct Reporter *r, const char *fmt, ...)
    __attribute__((__format__(printf, 2, 3)));

static void reportfmt(struct Reporter *r, const char *fmt, ...) {
    if (!r) return;
    jmethodID mid = reporter_method(r->env, r->obj);
    if (!mid) return;
    va_list va; va_start(va, fmt);
    char buf[1024]; vsnprintf(buf, sizeof(buf), fmt, va);
    va_end(va);
    jstring s = (*r->env)->NewStringUTF(r->env, buf);
    (*r->env)->CallVoidMethod(r->env, r->obj, mid, s);
    (*r->env)->ExceptionClear(r->env);
    (*r->env)->DeleteLocalRef(r->env, s);
}
#define REPORTLN(fmt, ...) reportfmt(reporter, fmt "\n" __VA_OPT__(,) __VA_ARGS__)
