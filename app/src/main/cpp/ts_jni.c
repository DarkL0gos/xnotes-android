// The whole JNI surface for code highlighting: marshals Java arrays and strings
// into the host-neutral core (native/highlight/xn_highlight.h). Stateless calls,
// so no native handles ever cross the boundary and there are no lifetimes to leak.
#include <jni.h>
#include <stdlib.h>

#include "xn_highlight.h"

_Static_assert(sizeof(XnSpan) == 3 * sizeof(jint), "XnSpan must pack as three jints");

// -> flat [startByte, endByte, captureIndex] triples, or null on unknown
//    language / malformed query.
JNIEXPORT jintArray JNICALL
Java_com_xnotes_platform_TreeSitterNative_nativeHighlight(
    JNIEnv *env, jclass cls, jstring jlang, jbyteArray jtext, jbyteArray jscm) {
    const char *lang = (*env)->GetStringUTFChars(env, jlang, NULL);
    if (!lang) return NULL;
    jbyte *scm = (*env)->GetByteArrayElements(env, jscm, NULL);
    jbyte *text = (*env)->GetByteArrayElements(env, jtext, NULL);

    jintArray out = NULL;
    XnSpan *spans;
    uint32_t n;
    if (scm && text &&
        xn_highlight(lang, (const char *)text, (uint32_t)(*env)->GetArrayLength(env, jtext),
                     (const char *)scm, (uint32_t)(*env)->GetArrayLength(env, jscm), &spans, &n)) {
        out = (*env)->NewIntArray(env, (jsize)(n * 3));
        if (out && n > 0) (*env)->SetIntArrayRegion(env, out, 0, (jsize)(n * 3), (const jint *)spans);
        xn_free_spans(spans);
    }

    if (text) (*env)->ReleaseByteArrayElements(env, jtext, text, JNI_ABORT);
    if (scm) (*env)->ReleaseByteArrayElements(env, jscm, scm, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, jlang, lang);
    return out;
}

// -> the query's capture names, indexed by the captureIndex nativeHighlight emits.
JNIEXPORT jobjectArray JNICALL
Java_com_xnotes_platform_TreeSitterNative_nativeCaptureNames(
    JNIEnv *env, jclass cls, jstring jlang, jbyteArray jscm) {
    const char *lang = (*env)->GetStringUTFChars(env, jlang, NULL);
    if (!lang) return NULL;
    jbyte *scm = (*env)->GetByteArrayElements(env, jscm, NULL);

    jobjectArray out = NULL;
    char **names;
    uint32_t count;
    if (scm && xn_capture_names(lang, (const char *)scm, (uint32_t)(*env)->GetArrayLength(env, jscm),
                                &names, &count)) {
        jclass str = (*env)->FindClass(env, "java/lang/String");
        out = str ? (*env)->NewObjectArray(env, (jsize)count, str, NULL) : NULL;
        for (uint32_t i = 0; out && i < count; i++) {
            jstring jname = (*env)->NewStringUTF(env, names[i]);
            (*env)->SetObjectArrayElement(env, out, (jsize)i, jname);
            (*env)->DeleteLocalRef(env, jname);
        }
        xn_free_names(names, count);
    }

    if (scm) (*env)->ReleaseByteArrayElements(env, jscm, scm, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, jlang, lang);
    return out;
}
