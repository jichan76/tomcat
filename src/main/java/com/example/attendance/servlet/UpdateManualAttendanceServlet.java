package com.example.attendance.servlet;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.example.attendance.model.StudentAttendance;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.*;
import java.lang.reflect.Type;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

@WebServlet("/updateManualAttendance")
public class UpdateManualAttendanceServlet extends HttpServlet {

    private static final Logger logger = Logger.getLogger(UpdateManualAttendanceServlet.class.getName());

    // DB 연결정보
    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        Properties p = new Properties();
        p.setProperty("user", "ADMIN");
        p.setProperty("password", "NewPassword123!");
        p.setProperty("oracle.net.CONNECT_TIMEOUT", "8000");
        p.setProperty("oracle.net.READ_TIMEOUT", "8000");
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, p);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        req.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json; charset=UTF-8");
        resp.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        resp.setHeader("Pragma", "no-cache");

        // ✅ 파라미터 수신
        String subjectIdStr = req.getParameter("subjectId");
        String weekStr = req.getParameter("week");

        if (subjectIdStr == null || weekStr == null) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"subjectId, week 파라미터 필요\"}");
            return;
        }

        int subjectId, week;
        try {
            subjectId = Integer.parseInt(subjectIdStr);
            week = Integer.parseInt(weekStr);
        } catch (NumberFormatException e) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"subjectId, week는 숫자여야 함\"}");
            return;
        }

        // ✅ JSON Body 파싱
        StringBuilder jsonBody = new StringBuilder();
        try (BufferedReader reader = req.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                jsonBody.append(line);
            }
        }

        Type listType = new TypeToken<List<StudentAttendance>>(){}.getType();
        List<StudentAttendance> updates;
        try {
            updates = new Gson().fromJson(jsonBody.toString(), listType);
        } catch (Exception e) {
            resp.setStatus(400);
            resp.getWriter().write("{\"error\":\"잘못된 JSON 형식\"}");
            return;
        }
// 출석마감을 할 떄 수정이 없으면 마감 안되는거 막는 기능인데 미체크 관련 코드 수정중 시간이 너무 오래 걸려서 우선 막지말고 오픈
//        if (updates == null || updates.isEmpty()) {
//            resp.setStatus(400);
//            resp.getWriter().write("{\"error\":\"업데이트할 데이터가 비어 있음\"}");
//            return;
//        }

        int updatedCount = 0;
        String sql = "MERGE INTO attendance_records ar " +
                "USING dual ON (ar.subject_id = ? AND ar.student_id = ? AND ar.week_number = ?) " +
                "WHEN MATCHED THEN UPDATE SET ar.status = ?, ar.attendance_datetime = SYSDATE " +
                "WHEN NOT MATCHED THEN INSERT (subject_id, student_id, week_number, status, attendance_datetime) " +
                "VALUES (?, ?, ?, ?, SYSDATE)";

        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);

            for (StudentAttendance u : updates) {
                if (u.getStudentId() == null || u.getStudentId().trim().isEmpty()) continue;
                if (u.getStatus() == null || u.getStatus().trim().isEmpty()) continue;

                int idx = 1;
                pstmt.setInt(idx++, subjectId);
                pstmt.setString(idx++, u.getStudentId());
                pstmt.setInt(idx++, week);
                pstmt.setString(idx++, u.getStatus());
                pstmt.setInt(idx++, subjectId);
                pstmt.setString(idx++, u.getStudentId());
                pstmt.setInt(idx++, week);
                pstmt.setString(idx++, u.getStatus());
                pstmt.addBatch();
            }

            int[] results = pstmt.executeBatch();
            conn.commit();
            updatedCount = results.length;

        } catch (Exception e) {
            logger.severe("updateManualAttendance error: " + e.getMessage());
            resp.setStatus(500);
            resp.getWriter().write("{\"error\":\"DB 오류: " + e.getMessage().replace("\"", "\\\"") + "\"}");
            return;
        }

        // ✅ 성공 응답
        Map<String, Object> result = new HashMap<>();
        result.put("result", "ok");
        result.put("updated", updatedCount);
        resp.getWriter().write(new Gson().toJson(result));
    }
}
