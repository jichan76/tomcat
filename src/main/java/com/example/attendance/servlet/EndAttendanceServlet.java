package com.example.attendance.servlet;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;

@WebServlet("/endAttendance")
public class EndAttendanceServlet extends HttpServlet {

    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        String user = "ADMIN";
        String password = "NewPassword123!";
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json; charset=UTF-8");
        PrintWriter out = resp.getWriter();

        String subjectIdStr = req.getParameter("subjectId");

        if (subjectIdStr == null || subjectIdStr.isEmpty()) {
            out.write("{\"result\":\"error\", \"message\":\"subjectId 누락\"}");
            return;
        }

        int subjectId;
        try {
            subjectId = Integer.parseInt(subjectIdStr);
        } catch (NumberFormatException e) {
            out.write("{\"result\":\"error\", \"message\":\"subjectId가 올바른 숫자가 아님\"}");
            return;
        }

        String sql = "UPDATE subjects SET professor_bssid = NULL WHERE subject_id = ?";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, subjectId);
            int affected = ps.executeUpdate();

            if (affected > 0) {
                out.write("{\"result\":\"success\"}");
            } else {
                out.write("{\"result\":\"error\", \"message\":\"해당 과목 없음\"}");
            }

        } catch (Exception e) {
            e.printStackTrace();
            resp.setStatus(500);
            out.write("{\"result\":\"error\", \"message\":\"DB 오류: " + e.getMessage() + "\"}");
        }
    }
}
