package com.example.attendance.servlet;

import java.io.*;
import java.sql.*;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import org.mindrot.jbcrypt.BCrypt;
import com.google.gson.JsonObject;

@WebServlet("/adminChangePassword")
public class AdminChangePasswordServlet extends HttpServlet {

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

        // 📌 안드로이드에서 전송한 파라미터명과 정확히 맞춰야 함!
        String userId = request.getParameter("userId");
        String tempPassword = request.getParameter("tempPassword");
        String role = request.getParameter("role");

        // 관리자 인증(세션, 토큰 등) 필요시 여기에 추가! (여기선 생략)
        if (userId == null || tempPassword == null || role == null
            || userId.trim().isEmpty() || tempPassword.trim().isEmpty() || role.trim().isEmpty()) {
            jsonResponse.addProperty("result", "fail");
            jsonResponse.addProperty("message", "파라미터 누락");
            out.print(jsonResponse.toString());
            return;
        }

        // role 값 체크 (student, professor만 허용)
        if (!role.equals("student") && !role.equals("professor")) {
            jsonResponse.addProperty("result", "fail");
            jsonResponse.addProperty("message", "역할(role) 값이 잘못되었습니다.");
            out.print(jsonResponse.toString());
            return;
        }

        try (Connection conn = getConnection()) {
            // 1. 임시비밀번호 해시 생성
            String hashedPassword = BCrypt.hashpw(tempPassword, BCrypt.gensalt());

            // 2. 사용자 존재 확인
            String checkSql = "SELECT COUNT(*) FROM users WHERE user_id = ?";
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setString(1, userId);
                ResultSet rs = checkStmt.executeQuery();
                if (rs.next() && rs.getInt(1) == 0) {
                    jsonResponse.addProperty("result", "fail");
                    jsonResponse.addProperty("message", "존재하지 않는 사용자입니다.");
                    out.print(jsonResponse.toString());
                    return;
                }
            }

            // 3. 비밀번호와 역할 동시 업데이트
            String updateSql = "UPDATE users SET password = ?, role = ? WHERE user_id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(updateSql)) {
                pstmt.setString(1, hashedPassword);
                pstmt.setString(2, role);
                pstmt.setString(3, userId);

                int affectedRows = pstmt.executeUpdate();
                if (affectedRows > 0) {
                    jsonResponse.addProperty("result", "success");
                    jsonResponse.addProperty("message", "비밀번호/역할이 변경되었습니다.");
                } else {
                    jsonResponse.addProperty("result", "fail");
                    jsonResponse.addProperty("message", "업데이트 실패 (row 없음)");
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
