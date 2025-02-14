package me.zolotov.oniguruma

class Oniguruma private constructor() {
    companion object {
        val instance: Oniguruma by lazy {
//            System.loadLibrary("librust_oniguruma_bindings")
            System.load("/Users/zolotov/dev/oniguruma-jni/src/rust-oniguruma-bindings/target/release/librust_oniguruma_bindings.dylib")
            Oniguruma()
        }
    }

    external fun match(
        regexPtr: Long,
        textPtr: Long,
        byteOffset: Int,
        matchBeginPosition: Boolean,
        matchBeginString: Boolean,
    ): IntArray?

    external fun createRegex(pattern: ByteArray): Long

    external fun freeRegex(regexPtr: Long)

    external fun createString(utf8Content: ByteArray): Long

    external fun freeString(textPtr: Long)
}