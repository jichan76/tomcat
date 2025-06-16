package com.example.attendance.servlet;

import com.example.attendance.model.ManualAttendanceResponse;
import com.example.attendance.model.StudentAttendance;
import com.google.gson.Gson;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
@WebServlet("/getManualAttendanceList")
public class GetManualAttendanceListServlet extends HttpServlet {
    private static final Logger logger = Logger.getLogger(StartAttendanceServlet.class.getName());
    private Connection getConnection() throws Exception {
        String url = "jdbc:oracle:thin:@appdb_high?TNS_ADMIN=/opt/wallet";
        String user = "ADMIN";
        String password = "NewPassword123!";
        Class.forName("oracle.jdbc.driver.OracleDriver");
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        // 1. 파라미터 추출
        String subjectIdStr = request.getParameter("subjectId");
        String weekStr = request.getParameter("week");
        int subjectId = Integer.parseInt(subjectIdStr);
        int week = Integer.parseInt(weekStr);

        // 2. 데이터 조회
        List<StudentAttendance> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(
                     "SELECT ar.student_id, u.name, ar.status " +
                             "FROM attendance_records ar " +
                             "JOIN users u ON ar.student_id = u.user_id " +
                             "WHERE ar.subject_id = ? AND ar.week_number = ? " +
                             "ORDER BY ar.student_id"
             )) {
            pstmt.setInt(1, subjectId);
            pstmt.setInt(2, week);

            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                String studentId = rs.getString("student_id");
                String name = rs.getString("name");
                String status = rs.getString("status");
                if ("지각_조퇴".equals(status)) {
                    status = "지각/조퇴";
                }
                list.add(new StudentAttendance(studentId, name, status));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 3. JSON 변환 및 응답
        ManualAttendanceResponse respObj = new ManualAttendanceResponse(list);
        String json = new Gson().toJson(respObj);

        response.setContentType("application/json; charset=UTF-8");
        response.getWriter().write(json);
    }
}
