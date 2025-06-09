package com.example.servlet;

import javax.mail.*;
import javax.mail.internet.*;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@WebServlet("/EmailApi")
public class EmailApi extends HttpServlet {

    // 인증 코드와 생성시간을 같이 저장하는 클래스
    private static class CodeData {
        private final String code;
        private final long timestamp;

        public CodeData(String code) {
            this.code = code;
            this.timestamp = System.currentTimeMillis();
        }

        public String getCode() {
            return code;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    // 스레드 안전한 Map 사용
    private static Map<String, CodeData> authCodes = new ConcurrentHashMap<>();

    // 인증 코드 만료 시간 5분 (밀리초 단위)
    private static final long CODE_EXPIRE_TIME = 5 * 60 * 1000;

    // 구글 SMTP 계정 설정 - 실제 환경에 맞게 바꿔주세요
    private final String SMTP_USERNAME = "bsattendcheck@gmail.com";        // 구글 이메일
    private final String SMTP_PASSWORD = "rehn qyfz drhg jfdw";           // 구글 앱 비밀번호
    private final String SMTP_HOST = "smtp.gmail.com";
    private final int SMTP_PORT = 587;
    private final String EMAIL_FROM_NAME = "ATC";              // 발신자명
    private final String EMAIL_SUBJECT = "인증 코드 발송 안내";

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=UTF-8");

        JsonObject jsonResponse = new JsonObject();

        String action = request.getParameter("action");
        String email = request.getParameter("email");

        if (action == null || email == null || email.isEmpty()) {
            jsonResponse.addProperty("result", "fail");
            jsonResponse.addProperty("message", "Missing action or email");
            writeResponse(response, jsonResponse.toString());
            return;
        }

        switch (action) {
            case "sendCode":
                String code = generateAuthCode();
                CodeData codeData = new CodeData(code);
                authCodes.put(email, codeData);
                boolean emailSent = sendEmail(email, code);
                if (emailSent) {
                    jsonResponse.addProperty("result", "success");
                } else {
                    jsonResponse.addProperty("result", "fail");
                    jsonResponse.addProperty("message", "Email sending failed");
                }
                break;

            case "verifyCode":
                String inputCode = request.getParameter("code");
                if (inputCode == null || inputCode.isEmpty()) {
                    jsonResponse.addProperty("result", "fail");
                    jsonResponse.addProperty("message", "Missing code");
                } else {
                    CodeData savedData = authCodes.get(email);
                    if (savedData == null) {
                        jsonResponse.addProperty("result", "fail");
                        jsonResponse.addProperty("message", "No code found");
                    } else {
                        long currentTime = System.currentTimeMillis();
                        if (currentTime - savedData.getTimestamp() > CODE_EXPIRE_TIME) {
                            jsonResponse.addProperty("result", "fail");
                            jsonResponse.addProperty("message", "Code expired");
                            authCodes.remove(email); // 만료된 코드 삭제
                        } else if (inputCode.equals(savedData.getCode())) {
                            jsonResponse.addProperty("result", "success");
                            authCodes.remove(email); // 성공하면 코드 제거
                        } else {
                            jsonResponse.addProperty("result", "fail");
                            jsonResponse.addProperty("message", "Invalid code");
                        }
                    }
                }
                break;

            default:
                jsonResponse.addProperty("result", "fail");
                jsonResponse.addProperty("message", "Unknown action");
        }

        writeResponse(response, jsonResponse.toString());
    }

    private boolean sendEmail(String toEmail, String code) {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", SMTP_HOST);
        props.put("mail.smtp.port", String.valueOf(SMTP_PORT));

        Session session = Session.getInstance(props, new javax.mail.Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(SMTP_USERNAME, SMTP_PASSWORD);
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(SMTP_USERNAME, EMAIL_FROM_NAME));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
            message.setSubject(EMAIL_SUBJECT);
            String content = "안녕하세요.\n\n인증 코드는 다음과 같습니다:\n\n" + code + "\n\n감사합니다.";
            message.setText(content);

            Transport.send(message);
            System.out.println("[EmailApi] Email sent to " + toEmail + " with code " + code);
            return true;
        } catch (Exception e) {
            System.err.println("[EmailApi] Email send failed:");
            e.printStackTrace();
            return false;
        }
    }

    private String generateAuthCode() {
        Random rnd = new Random();
        int number = 100000 + rnd.nextInt(900000);
        return String.valueOf(number);
    }

    private void writeResponse(HttpServletResponse response, String json) throws IOException {
        try (PrintWriter out = response.getWriter()) {
            out.print(json);
            out.flush();
        }
    }
}
