package com.example.attendance.servlet;

import javax.mail.*;
import javax.mail.internet.*;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@WebServlet("/EmailApi")
public class EmailApi extends HttpServlet {

    private static final Logger log = Logger.getLogger(EmailApi.class.getName());

    // 전역 스로틀링(메일 발송 병렬 제한)
    private static final int MAX_CONCURRENT = 8;
    private static final int ACQUIRE_MS     = 500;
    private static final Semaphore GUARD = new Semaphore(MAX_CONCURRENT, true);

    private static class CodeData {
        private final String codeHash;
        private final long timestamp;
        private int attempts;
        public CodeData(String codeHash) { this.codeHash = codeHash; this.timestamp = System.currentTimeMillis(); this.attempts = 0; }
    }
    private static final Map<String, CodeData> authCodes = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastSendAt = new ConcurrentHashMap<>();
    private static final Map<String, Integer> sendCountHour = new ConcurrentHashMap<>();
    private static final Map<String, Long> sendCountWindowStart = new ConcurrentHashMap<>();

    private static final long CODE_EXPIRE_MS = 5 * 60 * 1000;
    private static final long SEND_COOLDOWN_MS = 60 * 1000;
    private static final int  MAX_SEND_PER_HOUR = 5;
    private static final int  MAX_VERIFY_ATTEMPTS = 5;

    private String SMTP_USERNAME;
    private String SMTP_PASSWORD;
    private String SMTP_HOST = "smtp.gmail.com";
    private int    SMTP_PORT = 587;
    private String EMAIL_FROM_NAME = "ATC";
    private String EMAIL_SUBJECT   = "인증 코드 안내";

    @Override
    public void init() throws ServletException {
        SMTP_USERNAME = System.getenv().getOrDefault("SMTP_USERNAME", "bsattendcheck@gmail.com");
        SMTP_PASSWORD = System.getenv().getOrDefault("SMTP_APP_PASSWORD", "etwr ykhv oggq ppfc");
        String host = System.getenv("SMTP_HOST"); String port = System.getenv("SMTP_PORT");
        if (host != null && !host.isEmpty()) SMTP_HOST = host;
        if (port != null && !port.isEmpty()) { try { SMTP_PORT = Integer.parseInt(port); } catch (NumberFormatException ignored) {} }
        String fromName = System.getenv("EMAIL_FROM_NAME");
        if (fromName != null && !fromName.isEmpty()) EMAIL_FROM_NAME = fromName;
        String subject = System.getenv("EMAIL_SUBJECT");
        if (subject != null && !subject.isEmpty()) EMAIL_SUBJECT = subject;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        boolean acquired = false;
        try {
            acquired = GUARD.tryAcquire(ACQUIRE_MS, TimeUnit.MILLISECONDS);
            if (!acquired) { response.setStatus(503); write(response, fail("busy")); return; }

            request.setCharacterEncoding("UTF-8");
            response.setContentType("application/json; charset=UTF-8");
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");

            JsonObject res = new JsonObject();
            String action = trim(request.getParameter("action"));
            String emailRaw = trim(request.getParameter("email"));
            if (action == null || emailRaw == null) { write(response, fail("Missing action or email")); return; }

            String email = emailRaw.toLowerCase(Locale.ROOT);
            if (!isValidEmail(email)) { write(response, fail("Invalid email")); return; }

            switch (action) {
                case "sendCode": {
                    long now = System.currentTimeMillis();
                    Long last = lastSendAt.get(email);
                    if (last != null && now - last < SEND_COOLDOWN_MS) { write(response, fail("Too many requests. Try again later.")); return; }
                    resetHourlyWindowIfNeeded(email, now);
                    int cnt = sendCountHour.getOrDefault(email, 0);
                    if (cnt >= MAX_SEND_PER_HOUR) { write(response, fail("Hourly limit reached. Try again later.")); return; }

                    String code = generateAuthCode();
                    String codeHash = sha256(code);
                    authCodes.put(email, new CodeData(codeHash));

                    boolean sent = sendEmail(email, code);
                    if (sent) {
                        lastSendAt.put(email, now);
                        sendCountHour.put(email, cnt + 1);
                        res.addProperty("result", "success");
                        res.addProperty("message", "Code sent");
                        write(response, res.toString());
                    } else {
                        write(response, fail("Email sending failed"));
                    }
                    break;
                }
                case "verifyCode": {
                    String inputCode = trim(request.getParameter("code"));
                    if (inputCode == null) { write(response, fail("Missing code")); return; }
                    CodeData saved = authCodes.get(email);
                    if (saved == null) { write(response, fail("No code found")); return; }
                    long elapsed = System.currentTimeMillis() - saved.timestamp;
                    if (elapsed > CODE_EXPIRE_MS) { authCodes.remove(email); write(response, fail("Code expired")); return; }
                    if (saved.attempts >= MAX_VERIFY_ATTEMPTS) { authCodes.remove(email); write(response, fail("Too many attempts")); return; }
                    saved.attempts++;

                    String inHash = sha256(inputCode);
                    if (constantTimeEquals(inHash, saved.codeHash)) {
                        authCodes.remove(email);
                        res.addProperty("result", "success");
                        res.addProperty("message", "Verified");
                        write(response, res.toString());
                    } else {
                        write(response, fail("Invalid code"));
                    }
                    break;
                }
                default:
                    write(response, fail("Unknown action"));
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt(); response.setStatus(503);
        } catch (Exception e) {
            response.setStatus(500); write(response, error("Server error"));
        } finally {
            if (acquired) GUARD.release();
        }
    }

    private boolean sendEmail(String toEmail, String code) {
        if (SMTP_PASSWORD == null || SMTP_PASSWORD.isEmpty()) { log.warning("SMTP app password not set."); return false; }
        Properties props = new Properties();
        props.put("mail.smtp.auth","true");
        props.put("mail.smtp.starttls.enable","true");
        props.put("mail.smtp.host", SMTP_HOST);
        props.put("mail.smtp.port", String.valueOf(SMTP_PORT));
        props.put("mail.smtp.ssl.trust", SMTP_HOST);

        Session session = Session.getInstance(props, new javax.mail.Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() { return new PasswordAuthentication(SMTP_USERNAME, SMTP_PASSWORD); }
        });

        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(SMTP_USERNAME, EMAIL_FROM_NAME, StandardCharsets.UTF_8.name()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail, false));
            message.setSubject(EMAIL_SUBJECT + " (유효기간 5분)", StandardCharsets.UTF_8.name());
            String content =
                    "<div style='font-family:system-ui,Apple SD Gothic Neo,Segoe UI,sans-serif;font-size:14px'>" +
                            "<p>안녕하세요.</p>" +
                            "<p>요청하신 인증 코드는 <b style='font-size:16px'>" + code + "</b> 입니다.</p>" +
                            "<p>본 코드는 발송 시점부터 5분간 유효하며, 타인에게 공유하지 마세요.</p>" +
                            "<p>본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다.</p>" +
                            "<hr><p style='color:#888'>ATC 시스템</p></div>";
            message.setContent(content, "text/html; charset=UTF-8");
            Transport.send(message);
            log.info("[EmailApi] Sent code to " + toEmail);
            return true;
        } catch (Exception e) {
            log.severe("[EmailApi] Email send failed: " + e.getMessage());
            return false;
        }
    }

    // 유틸
    private static String generateAuthCode() { int n = new Random().nextInt(1_000_000); return String.format("%06d", n); }
    private static String sha256(String s) {
        try { MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(); for (byte b: dig) sb.append(String.format("%02x", b)); return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false; int len = Math.max(a.length(), b.length()); int res = 0;
        for (int i=0;i<len;i++){ char ca = i<a.length()?a.charAt(i):0; char cb = i<b.length()?b.charAt(i):0; res|=(ca^cb); }
        return res==0 && a.length()==b.length();
    }
    private static String trim(String s){ if(s==null) return null; String t=s.trim(); return t.isEmpty()?null:t; }
    private static boolean isValidEmail(String email){ try { InternetAddress ia=new InternetAddress(email); ia.validate(); return true; } catch(AddressException e){ return false; } }
    private static String error(String msg){ JsonObject o=new JsonObject(); o.addProperty("result","error"); o.addProperty("message",msg); return o.toString(); }
    private static String fail (String msg){ JsonObject o=new JsonObject(); o.addProperty("result","fail");  o.addProperty("message",msg); return o.toString(); }
    private static void write(HttpServletResponse response, String json) throws IOException { try(PrintWriter out=response.getWriter()){ out.print(json); out.flush(); } }
    private void resetHourlyWindowIfNeeded(String email, long now) {
        long start = sendCountWindowStart.getOrDefault(email, 0L);
        if (now - start >= 60 * 60 * 1000) { sendCountWindowStart.put(email, now); sendCountHour.put(email, 0); }
    }
}
