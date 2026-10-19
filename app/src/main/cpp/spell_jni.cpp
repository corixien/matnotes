// JNI wrapper around Hunspell (MPL 1.1 / GPL 2 / LGPL 2.1, see hunspell/COPYING.MPL) for the spelling check.
#include <jni.h>
#include <string>
#include <vector>
#include "hunspell.hxx"

static std::string utf(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_matnotes_app_HunspellNative_open(JNIEnv* env, jclass, jstring aff, jstring dic) {
    return reinterpret_cast<jlong>(new Hunspell(utf(env, aff).c_str(), utf(env, dic).c_str()));
}

JNIEXPORT void JNICALL Java_com_matnotes_app_HunspellNative_close(JNIEnv*, jclass, jlong h) {
    delete reinterpret_cast<Hunspell*>(h);
}

JNIEXPORT jboolean JNICALL Java_com_matnotes_app_HunspellNative_spell(JNIEnv* env, jclass, jlong h, jstring w) {
    return reinterpret_cast<Hunspell*>(h)->spell(utf(env, w)) != 0;
}

JNIEXPORT void JNICALL Java_com_matnotes_app_HunspellNative_add(JNIEnv* env, jclass, jlong h, jstring w) {
    reinterpret_cast<Hunspell*>(h)->add(utf(env, w));
}

JNIEXPORT jobjectArray JNICALL Java_com_matnotes_app_HunspellNative_suggest(JNIEnv* env, jclass, jlong h, jstring w) {
    std::vector<std::string> out = reinterpret_cast<Hunspell*>(h)->suggest(utf(env, w));
    jclass str = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(out.size()), str, nullptr);
    for (size_t i = 0; i < out.size(); i++) {
        jstring s = env->NewStringUTF(out[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

}
