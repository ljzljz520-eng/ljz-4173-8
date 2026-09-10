package org.bstraining.stream;

import org.bstraining.model.SimEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.util.TreeMap;

/**
 * 会话内事件哈希链：hash_n = SHA-256(hash_{n-1} || 规范化字段)。
 * 复盘时可重新计算比对，任何对原事件的编辑都会断链。
 */
public final class EventHashChain {

    private String prev = "GENESIS";

    public SimEvent chain(SimEvent e) {
        String canonical = canonical(e);
        String h = sha256(prev + "|" + canonical);
        SimEvent out = e.withChain(prev, h);
        prev = h;
        return out;
    }

    /** 不改变内部游标，仅验证给定事件是否与前一哈希衔接。 */
    public static boolean verify(java.util.List<SimEvent> events) {
        String p = "GENESIS";
        for (SimEvent e : events) {
            String expect = sha256(p + "|" + canonical(e));
            if (!expect.equals(e.hash()) || !p.equals(e.prevHash())) {
                return false;
            }
            p = expect;
        }
        return true;
    }

    public static String canonical(SimEvent e) {
        // TreeMap 保证字段顺序稳定
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("eventId", nz(e.eventId()));
        m.put("seq", e.seq());
        m.put("commandSeq", e.commandSeq() == null ? "" : e.commandSeq().toString());
        m.put("type", e.type().name());
        m.put("elementId", nz(e.elementId()));
        m.put("message", nz(e.message()));
        m.put("payloadJson", nz(e.payloadJson()));
        m.put("simTime", e.simTime() == null ? "" : DateTimeFormatter.ISO_INSTANT.format(e.simTime()));
        m.put("producedAt", e.producedAt() == null ? "" : DateTimeFormatter.ISO_INSTANT.format(e.producedAt()));
        return m.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
