package com.example.attendance.servlet;

import com.example.attendance.repository.InMemoryDB;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.logging.Logger;
import com.google.gson.JsonObject;

@WebServlet("/startAttendance")
public class StartAttendanceServlet extends HttpServlet {
    private static final Logger logger = Logger.getLogger(StartAttendanceServlet.class.getName());

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
        String bssid = req.getParameter("bssid");
        JsonObject result = new JsonObject();

        if (subjectIdStr == null || bssid == null || subjectIdStr.isEmpty() || bssid.isEmpty()) {
            result.addProperty("result", "error");
            result.addProperty("message", "subjectId 또는 bssid가 누락됨");
            out.print(result.toString());
            return;
        }

        int subjectId = Integer.parseInt(subjectIdStr);
        // 1. DB에 BSSID 저장 (UPDATE)
        String sql = "UPDATE subjects SET professor_bssid = ? WHERE subject_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, bssid);
            ps.setInt(2, subjectId);

            int affected = ps.executeUpdate();
            if (affected > 0) {
                result.addProperty("result", "started");
            } else {
                result.addProperty("result", "error");
                result.addProperty("message", "해당 subject_id의 과목이 없음");
            }
        } catch (Exception e) {
            result.addProperty("result", "error");
            result.addProperty("message", "DB 오류: " + e.getMessage());
        }
        InMemoryDB.ATTENDANCE.put(subjectId, new java.util.HashSet<>());

        out.print(result.toString());
    }
}
