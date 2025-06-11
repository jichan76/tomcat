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

                    // [관리자: 초기일 때만 평문 허용, 그 외 해시]
                    if ("admin".equals(role) && isInitialAdmin == 1) {
                        if (dbPassword != null && dbPassword.startsWith("$2")) {
                            passwordMatched = BCrypt.checkpw(password, dbPassword);
                        } else {
                            passwordMatched = password.equals(dbPassword);
                        }
                    } else {
                        // 학생/교수/일반관리자 모두 해싱만 허용
                        if (dbPassword != null && dbPassword.startsWith("$2")) {
                            passwordMatched = BCrypt.checkpw(password, dbPassword);
                        }
                    }

                    if (passwordMatched) {
                        if ("student".equals(role)) {
                       // Android ID 등록/검증은 기존과 동일
                            if (dbAndroidId == null || dbAndroidId.isEmpty()) {
                                String updateSql = "UPDATE users SET android_id = ? WHERE user_id = ?";
                                try (PreparedStatement updatePstmt = conn.prepareStatement(updateSql)) {
                                    updatePstmt.setString(1, androidId);
                                    updatePstmt.setString(2, userId);
                                    updatePstmt.executeUpdate();
                                }
                            }
                        } else if (!dbAndroidId.equals(androidId)) {
                            jsonResponse.addProperty("result", "fail");
                            jsonResponse.addProperty("message", "등록된 기기와 다릅니다");
                            out.print(jsonResponse.toString());
                            return;
                        }
                        jsonResponse.addProperty("result", "success");
                        jsonResponse.addProperty("role", role);
                        jsonResponse.addProperty("name", name);
                        jsonResponse.addProperty("user_id", userId);
                        jsonResponse.addProperty("is_first_login", isFirstLogin);         // << 추가!
                        jsonResponse.addProperty("is_initial_admin", isInitialAdmin);     // << 추가!
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
            e.printStackTrace();
        }
        out.print(jsonResponse.toString());
    }
}
