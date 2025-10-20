package com.example.attendance.servlet;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.Properties;

@WebServlet("/GetCurrentSubjectServlet")
public class GetCurrentSubjectServlet extends HttpServlet {

    // --- DB 연결 타임아웃 설정값 ---
    private static final int DB_CONNECT_TIMEOUT_MS = 8000;
    private static final int DB_READ_TIMEOUT_MS = 8000;

    // --- DB 연결 메서드 ---
    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";

        Properties p = new Properties();
        p.setProperty("user", "ADMIN");
        p.setProperty("password", "NewPassword123!");
        p.setProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(DB_CONNECT_TIMEOUT_MS));
        p.setProperty("oracle.net.READ_TIMEOUT", String.valueOf(DB_READ_TIMEOUT_MS));

        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, p);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=UTF-8");

        String studentId = request.getParameter("student_id");
        JSONArray arr = new JSONArray();

        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            // ✅ DB 연결
            conn = getConnection();

            // ✅ 학생이 수강 중이며 교수의 BSSID가 존재하는 과목만 조회
            String sql =
                    "SELECT S.NAME AS SUBJECT_NAME, S.PROFESSOR_BSSID " +
                            "FROM SUBJECTS S " +
                            "JOIN ENROLLMENTS E ON S.SUBJECT_ID = E.SUBJECT_ID " +
                            "WHERE E.STUDENT_ID = ? ";

            pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, studentId);
            rs = pstmt.executeQuery();

            // ✅ JSON 배열 생성
            while (rs.next()) {
                JSONObject obj = new JSONObject();
                obj.put("SUBJECT_TITLE", rs.getString("SUBJECT_NAME"));
                obj.put("PROFESSOR_BSSID", rs.getString("PROFESSOR_BSSID"));
                arr.put(obj);
            }

        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (pstmt != null) pstmt.close(); } catch (Exception ignore) {}
            try { if (conn != null) conn.close(); } catch (Exception ignore) {}
        }

        // ✅ 결과 출력
        PrintWriter out = response.getWriter();
        out.print(arr.toString());
        out.flush();
    }
}
