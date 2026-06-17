package me.zolotov.oniguruma.jni;

import java.nio.file.Path;

public final class Oniguruma {
    private Oniguruma() {
    }

    public static Oniguruma createFromResources() {
        OnigurumaLoader.loadFromResources();
        return new Oniguruma();
    }

    public static Oniguruma createFromFile(Path path) {
        OnigurumaLoader.loadFromFile(path);
        return new Oniguruma();
    }

    public native int[] match(
            long regexPtr,
            long textPtr,
            int byteOffset,
            boolean matchBeginPosition,
            boolean matchBeginString
    );

    public native long createRegex(byte[] pattern);

    public native void freeRegex(long regexPtr);

    public native long createString(byte[] utf8Content);

    public native void freeString(long textPtr);
}
