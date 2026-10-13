package com.excel2mysql.core;

import com.excel2mysql.pinyin.PinyinConverter;

import java.io.IOException;
import java.io.UncheckedIOException;

/** 词典只加载一次：41923 条单字加 47111 条词组，重复解折成本很高。 */
public final class PinyinLoader {

    private static volatile PinyinConverter cached;

    private PinyinLoader() {
    }

    public static PinyinConverter get() {
        PinyinConverter c = cached;
        if (c == null) {
            synchronized (PinyinLoader.class) {
                c = cached;
                if (c == null) {
                    try {
                        c = PinyinConverter.loadFromResources();
                    } catch (IOException e) {
                        throw new UncheckedIOException("拼音词典加载失败", e);
                    }
                    cached = c;
                }
            }
        }
        return c;
    }
}
