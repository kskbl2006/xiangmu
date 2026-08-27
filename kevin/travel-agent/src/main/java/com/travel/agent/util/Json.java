package com.travel.agent.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Map/JSON 数据访问工具。
 *
 * 全链路统一使用 Map&lt;String,Object&gt; 传递结构化数据（对齐 Python 原版 dict 设计），
 * checkpoint 落盘 / 缓存 / 工具入参出参共用同一数据模型，Jackson 序列化天然兼容。
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    /** 新建有序 Map（LinkedHashMap 保持字段顺序，JSON 落盘可读）。kv 成对出现。 */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** 新建可变列表（禁止 List.of：下游校验层会原地增删元素）。 */
    @SafeVarargs
    public static <T> List<T> arr(T... items) {
        List<T> list = new ArrayList<>();
        for (T t : items) {
            if (t != null) {
                list.add(t);
            }
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getMap(Map<String, Object> m, String key) {
        if (m == null) {
            return null;
        }
        Object v = m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    /** 取列表的弱类型视图（缺省空列表，元素保持原引用）。 */
    @SuppressWarnings("unchecked")
    public static List<Object> getList(Map<String, Object> m, String key) {
        if (m == null) {
            return new ArrayList<>();
        }
        Object v = m.get(key);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    /** 取原始 List 引用（供调用方原地增删，如 itinerary.items）。 */
    @SuppressWarnings("unchecked")
    public static List<Object> rawList(Map<String, Object> m, String key) {
        if (m == null) {
            return null;
        }
        return (List<Object>) m.get(key);
    }

    /** 把 Object 识别为 List&lt;Map&gt;（元素保持原引用，便于原地修改）。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> maps(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                if (x instanceof Map) {
                    out.add((Map<String, Object>) x);
                }
            }
        }
        return out;
    }

    public static List<Map<String, Object>> getMaps(Map<String, Object> m, String key) {
        return maps(m == null ? null : m.get(key));
    }

    /**
     * 取原始 List&lt;Map&gt; 引用（供调用方原地增删，如 parseKb 往 kb 的 attractions 列表追加条目）。
     * 与 getMaps 不同：本方法返回的是 Map 中存储的真实列表引用，不拷贝。
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> rawMaps(Map<String, Object> m, String key) {
        if (m == null) {
            return null;
        }
        Object v = m.get(key);
        return v instanceof List ? (List<Map<String, Object>>) v : null;
    }

    public static String getStr(Map<String, Object> m, String key) {
        if (m == null) {
            return "";
        }
        return str(m.get(key));
    }

    public static String getStr(Map<String, Object> m, String key, String def) {
        if (m == null) {
            return def;
        }
        Object v = m.get(key);
        return v == null ? def : str(v);
    }

    public static String str(Object o) {
        if (o == null) {
            return "";
        }
        if (o instanceof Double d) {
            if (!d.isInfinite() && !d.isNaN() && d == Math.floor(d) && Math.abs(d) < 1e15) {
                return String.valueOf(d.longValue());
            }
            return d.toString();
        }
        return o.toString();
    }

    public static int intVal(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return (int) Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    public static int getInt(Map<String, Object> m, String key) {
        return m == null ? 0 : intVal(m.get(key));
    }

    public static double dblVal(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    public static double getDbl(Map<String, Object> m, String key) {
        return m == null ? 0.0 : dblVal(m.get(key));
    }

    public static boolean boolVal(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number n) {
            return n.doubleValue() != 0;
        }
        return o != null && Boolean.parseBoolean(o.toString());
    }

    public static boolean getBool(Map<String, Object> m, String key) {
        return m != null && boolVal(m.get(key));
    }

    /** 截取前 n 个字符（对应 Python 切片，用于描述截断省 token）。 */
    public static String cut(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n);
    }
}
