package com.example.servlet;

import java.io.*;
import java.sql.*;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;

import com.google.gson.JsonObject;
import org.mindrot.jbcrypt.BCrypt;

@WebServlet("/ChangePasswordServlet")
public class ChangePasswordServlet extends HttpServlet {

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

        String userId = request.getParameter("id");           // id 파라미터명 맞춰서 받기
        String newPassword = request.getParameter("newPassword");

        if (userId == null || newPassword == null || userId.isEmpty() || newPassword.isEmpty()) {
            jsonResponse.addProperty("result", "error");
            jsonResponse.addProperty("message", "아이디 또는 새 비밀번호가 없습니다.");
            out.print(jsonResponse.toString());
            return;
        }

        try (Connection conn = getConnection()) {
            // 비밀번호 해싱
            String hashedPassword = BCrypt.hashpw(newPassword, BCrypt.gensalt());

            String sql = "UPDATE users SET password = ? WHERE user_id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, hashedPassword);
                pstmt.setString(2, userId);

                int affectedRows = pstmt.executeUpdate();
                if (affectedRows > 0) {
                    jsonResponse.addProperty("result", "success");
                    jsonResponse.addProperty("message", "비밀번호가 성공적으로 변경되었습니다.");
                } else {
                    jsonResponse.addProperty("result", "fail");
                    jsonResponse.addProperty("message", "사용자를 찾을 수 없습니다.");
                }
            }
        } catch (Exception e) {
            jsonResponse.addProperty("result", "error");
            jsonResponse.addProperty("message", "서버 오류: " + e.getMessage());
            e.printStackTrace();
        }

        out.print(jsonResponse.toString());
    }
}
