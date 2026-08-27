package com.travel.agent.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** 统一日志输出：HH:mm:ss [TAG] message，synchronized 防并行子任务日志交错。 */
public final class Log {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Log() {
    }

    public static synchronized void info(String tag, String msg) {
        System.out.printf("%s [%s] %s%n", LocalTime.now().format(FMT), tag, msg);
    }
}
