package com.example.attendance.servlet;

import java.io.*;
import java.sql.*;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import org.mindrot.jbcrypt.BCrypt;

import com.google.gson.JsonObject;

@WebServlet("/LoginServlet")
public class LoginServlet extends HttpServlet {

    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        String user = "ADMIN";
        String password = "NewPassword123!";
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=UTF-8");
        PrintWriter out = response.getWriter();
        JsonObject jsonResponse = new JsonObject();

        String userId = request.getParameter("id");
        String password = request.getParameter("password");
        String androidId = request.getParameter("android_id");

        if (userId == null || password == null) {
            jsonResponse.addProperty("result", "error");
            jsonResponse.addProperty("message", "Missing id or password");
            out.print(jsonResponse.toString());
            return;
        }
        try (Connection conn = getConnection()) {
            String sql = "SELECT password, android_id, name, role, is_first_login, is_initial_admin FROM users WHERE user_id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, userId);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    String dbPassword = rs.getString("password");
                    String dbAndroidId = rs.getString("android_id");
                    String name = rs.getString("name");
                    String role = rs.getString("role");
                    int isFirstLogin = rs.getInt("is_first_login");
                    int isInitialAdmin = rs.getInt("is_initial_admin");

                    boolean passwordMatched = false;

                    // BCrypt로 비밀번호를 검증하는 것이 기본 로직
                    if (dbPassword != null && dbPassword.startsWith("$2a$")) { // BCrypt 해시 문자열은 '$2a$' 또는 '$2b$' 등으로 시작
                        passwordMatched = BCrypt.checkpw(password, dbPassword);
                    }
                    // 해싱되지 않은 (평문) 비밀번호인 경우 (초기 관리자 등 특정 상황에만 해당)
                    else {
                        // 특정 조건 (예: 'admin' 역할이면서 'is_initial_admin'이 1인 경우)에만 평문 비밀번호 비교 허용
                        if ("admin".equals(role) && isInitialAdmin == 1) {
                            passwordMatched = password.equals(dbPassword);
                            // TODO: 보안 강화: 초기 관리자 로그인 후에는 반드시 비밀번호를 BCrypt로 해싱하여 업데이트하는 로직 추가 필요
                            // 예시: 로그인 성공 후, isInitialAdmin이 1인 경우 비밀번호를 해싱하여 DB에 업데이트
                            // String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());
                            // ... update users SET password = ? WHERE user_id = ? ...
                        } else {
                            // 학생/교수/일반관리자의 평문 비밀번호는 허용하지 않음 (보안 정책)
                            // 이 경우 passwordMatched는 false로 유지되어 로그인 실패 처리됨
                        }
                    }

                    if (passwordMatched) {
                        // 사용자의 역할에 따라 Android ID 검증 로직 분기
                        if ("student".equals(role)) {
                            // 학생인 경우:
                            // 1. dbAndroidId가 없으면 새로 등록
                            if (dbAndroidId == null || dbAndroidId.isEmpty()) {
                                String updateSql = "UPDATE users SET android_id = ? WHERE user_id = ?";
                                try (PreparedStatement updatePstmt = conn.prepareStatement(updateSql)) {
                                    updatePstmt.setString(1, androidId);
                                    updatePstmt.setString(2, userId);
                                    updatePstmt.executeUpdate();
                                }
                            }
                            // 2. dbAndroidId가 있는데, 앱에서 보낸 androidId와 다르면 로그인 실패
                            else if (!dbAndroidId.equals(androidId)) { // dbAndroidId가 null/empty가 아닐 때만 이 조건에 들어옴
                                jsonResponse.addProperty("result", "fail");
                                jsonResponse.addProperty("message", "등록된 기기와 다릅니다");
                                out.print(jsonResponse.toString());
                                return;
                            }// 학생구간
                        } else { // 학생이 아닌 경우 (교수, 관리자)
                            // 교수나 관리자는 android_id가 필수 조건이 아닐 수 있음.
                            // 여기서는 dbAndroidId가 NULL일 가능성을 고려하여 안전하게 처리.
                            // 만약 기존에 android_id가 등록되어 있고, 앱에서 보낸 값과 다르면 실패 처리
                            if (dbAndroidId != null && androidId != null && !dbAndroidId.equals(androidId)) {
                                jsonResponse.addProperty("result", "fail");
                                jsonResponse.addProperty("message", "등록된 기기와 다릅니다");
                                out.print(jsonResponse.toString());
                                return;
                            }
                            // 참고: 만약 교수/관리자도 최초 로그인 시 Android ID를 등록해야 하는 로직이 필요하다면,
                            // 여기에 'dbAndroidId == null'인 경우 업데이트하는 로직을 추가할 수 있습니다.
                            // 예: if (dbAndroidId == null && androidId != null) { ... update users SET android_id ... }
                        }
                        // Android ID 검증을 통과했거나, 학생이 아닌 경우
                        jsonResponse.addProperty("result", "success");
                        jsonResponse.addProperty("role", role);
                        jsonResponse.addProperty("name", name);
                        jsonResponse.addProperty("user_id", userId);
                        jsonResponse.addProperty("is_first_login", isFirstLogin);
                        jsonResponse.addProperty("is_initial_admin", isInitialAdmin);
                    } else {
                        jsonResponse.addProperty("result", "fail");
                        jsonResponse.addProperty("message", "비밀번호 틀림");
                    }
                } else {
                    jsonResponse.addProperty("result", "fail");
                    jsonResponse.addProperty("message", "사용자 없음");
                }
            }
        } catch (Exception e) {
            jsonResponse.addProperty("result", "error");
            jsonResponse.addProperty("message", e.getMessage());
            e.printStackTrace(); // <-- 서버 로그(catalina.out)에 스택 트레이스를 출력하여 정확한 오류 확인
        }
        out.print(jsonResponse.toString());
    }
}
