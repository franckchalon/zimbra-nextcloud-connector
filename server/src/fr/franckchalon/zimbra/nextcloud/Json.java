package fr.franckchalon.zimbra.nextcloud;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON reader/writer used to keep the extension dependency-free. */
final class Json {
    private Json() {}

    static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            writeString((String) value, out);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Collection) {
            out.append('[');
            boolean first = true;
            for (Object item : (Collection<Object>) value) {
                if (!first) out.append(',');
                first = false;
                write(item, out);
            }
            out.append(']');
        } else if (value.getClass().isArray()) {
            out.append('[');
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (i > 0) out.append(',');
                write(java.lang.reflect.Array.get(value, i), out);
            }
            out.append(']');
        } else {
            writeString(String.valueOf(value), out);
        }
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    static Object parse(String source) {
        Parser parser = new Parser(source == null ? "" : source);
        Object result = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) throw parser.error("Contenu après la valeur JSON");
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String source) {
        Object parsed = parse(source);
        if (!(parsed instanceof Map)) throw new IllegalArgumentException("Un objet JSON est attendu");
        return (Map<String, Object>) parsed;
    }

    static String string(Map<String, Object> object, String key, String fallback) {
        Object value = object.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    static long longValue(Map<String, Object> object, String key, long fallback) {
        Object value = object.get(key);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value != null) {
            try { return Long.parseLong(String.valueOf(value)); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    static boolean bool(Map<String, Object> object, String key, boolean fallback) {
        Object value = object.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).longValue() != 0L;
        if (value instanceof String) {
            String text = ((String) value).trim();
            if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
            if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
        }
        return fallback;
    }

    private static final class Parser {
        private final String source;
        private int index;

        private Parser(String source) { this.source = source; }
        private boolean atEnd() { return index >= source.length(); }

        private void skipWhitespace() {
            while (!atEnd()) {
                char c = source.charAt(index);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') index++;
                else break;
            }
        }

        private Object readValue() {
            skipWhitespace();
            if (atEnd()) throw error("Valeur JSON manquante");
            char c = source.charAt(index);
            if (c == '{') return readObject();
            if (c == '[') return readArray();
            if (c == '"') return readString();
            if (c == 't') { expect("true"); return Boolean.TRUE; }
            if (c == 'f') { expect("false"); return Boolean.FALSE; }
            if (c == 'n') { expect("null"); return null; }
            if (c == '-' || Character.isDigit(c)) return readNumber();
            throw error("Valeur JSON invalide");
        }

        private Map<String, Object> readObject() {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            index++;
            skipWhitespace();
            if (!atEnd() && source.charAt(index) == '}') { index++; return result; }
            while (true) {
                skipWhitespace();
                if (atEnd() || source.charAt(index) != '"') throw error("Clé JSON attendue");
                String key = readString();
                skipWhitespace();
                if (atEnd() || source.charAt(index++) != ':') throw error("Deux-points attendu");
                result.put(key, readValue());
                skipWhitespace();
                if (atEnd()) throw error("Objet JSON non terminé");
                char separator = source.charAt(index++);
                if (separator == '}') return result;
                if (separator != ',') throw error("Virgule attendue");
            }
        }

        private List<Object> readArray() {
            ArrayList<Object> result = new ArrayList<>();
            index++;
            skipWhitespace();
            if (!atEnd() && source.charAt(index) == ']') { index++; return result; }
            while (true) {
                result.add(readValue());
                skipWhitespace();
                if (atEnd()) throw error("Tableau JSON non terminé");
                char separator = source.charAt(index++);
                if (separator == ']') return result;
                if (separator != ',') throw error("Virgule attendue");
            }
        }

        private String readString() {
            if (source.charAt(index++) != '"') throw error("Chaîne attendue");
            StringBuilder out = new StringBuilder();
            while (!atEnd()) {
                char c = source.charAt(index++);
                if (c == '"') return out.toString();
                if (c != '\\') {
                    if (c < 0x20) throw error("Caractère interdit dans une chaîne");
                    out.append(c);
                    continue;
                }
                if (atEnd()) throw error("Échappement incomplet");
                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (index + 4 > source.length()) throw error("Unicode incomplet");
                        try {
                            out.append((char) Integer.parseInt(source.substring(index, index + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("Unicode invalide");
                        }
                        index += 4;
                        break;
                    default: throw error("Échappement invalide");
                }
            }
            throw error("Chaîne non terminée");
        }

        private Number readNumber() {
            int start = index;
            if (source.charAt(index) == '-') index++;
            while (!atEnd() && Character.isDigit(source.charAt(index))) index++;
            boolean decimal = false;
            if (!atEnd() && source.charAt(index) == '.') {
                decimal = true;
                index++;
                while (!atEnd() && Character.isDigit(source.charAt(index))) index++;
            }
            if (!atEnd() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                decimal = true;
                index++;
                if (!atEnd() && (source.charAt(index) == '+' || source.charAt(index) == '-')) index++;
                while (!atEnd() && Character.isDigit(source.charAt(index))) index++;
            }
            String value = source.substring(start, index);
            try { return decimal ? Double.parseDouble(value) : Long.parseLong(value); }
            catch (NumberFormatException e) { throw error("Nombre invalide"); }
        }

        private void expect(String value) {
            if (!source.regionMatches(index, value, 0, value.length())) throw error("Valeur invalide");
            index += value.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " (position " + index + ")");
        }
    }
}
