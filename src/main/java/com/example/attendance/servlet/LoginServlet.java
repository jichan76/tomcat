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
            String sql = "SELECT password, android_id, name, role FROM users WHERE user_id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, userId);
                ResultSet rs = pstmt.executeQuery();

                if (rs.next()) {
                    String hashedPassword = rs.getString("password");
                    String dbAndroidId = rs.getString("android_id");
                    String name = rs.getString("name");
                    if (BCrypt.checkpw(password, hashedPassword)) {
                        // 비밀번호 일치
                        if (dbAndroidId == null || dbAndroidId.isEmpty()) {
                            String updateSql = "UPDATE users SET android_id = ? WHERE user_id = ?";
                            try (PreparedStatement updatePstmt = conn.prepareStatement(updateSql)) {
                                updatePstmt.setString(1, androidId);
                                updatePstmt.setString(2, userId);
                                updatePstmt.executeUpdate();
                            }
                        }else if (!dbAndroidId.equals(androidId)) {
                            // 등록된 기기와 다름
                            jsonResponse.addProperty("result", "fail");
                            jsonResponse.addProperty("message", "등록된 기기와 다릅니다");
                            out.print(jsonResponse.toString());
                            return;
                        }
                        jsonResponse.addProperty("result", "success");
                        jsonResponse.addProperty("role", rs.getString("role"));
                        jsonResponse.addProperty("name", rs.getString("name"));
                    } else {
                        // 비밀번호 불일치
                        jsonResponse.addProperty("result", "fail");
                        jsonResponse.addProperty("message", "비밀번호 틀림");
                    }
                } else {
                    // 아이디 없음
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
