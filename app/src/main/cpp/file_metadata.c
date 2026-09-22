#include <jni.h>
#include <string.h>
#include <sys/stat.h>

// Android's Java file attributes truncate modification times to whole seconds.
// Read the same nanosecond metadata used by the native memory-stick container.
JNIEXPORT jlongArray JNICALL
Java_io_github_bandoracer_rommio_domain_progress_NativeFileMetadata_modifiedTime(
        JNIEnv *env, jobject instance, jbyteArray encoded_path) {
    (void)instance;
    if (!encoded_path) return NULL;
    jsize size = (*env)->GetArrayLength(env, encoded_path);
    if (size <= 0 || size >= 4096) return NULL;
    char path[4096];
    (*env)->GetByteArrayRegion(env, encoded_path, 0, size, (jbyte *)path);
    if ((*env)->ExceptionCheck(env) || memchr(path, 0, size)) return NULL;
    path[size] = 0;
    struct stat metadata;
    if (lstat(path, &metadata) != 0 || !S_ISREG(metadata.st_mode)) return NULL;
    jlong values[2] = {(jlong)metadata.st_mtim.tv_sec, (jlong)metadata.st_mtim.tv_nsec};
    jlongArray result = (*env)->NewLongArray(env, 2);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, 2, values);
    return result;
}
