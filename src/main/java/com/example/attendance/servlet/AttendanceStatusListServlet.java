package com.example.attendance.servlet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.logging.Logger;

@WebServlet("/attendanceStatusList")
public class AttendanceStatusListServlet extends HttpServlet {
    private static final Logger logger = Logger.getLogger(SubjectsList.class.getName());

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

        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json; charset=UTF-8");

        String subjectId = request.getParameter("subjectId");

        String sqlAll =
                "SELECT u.user_id AS student_id, u.name " +
                        "FROM enrollments e " +
                        "JOIN users u ON u.user_id = e.student_id " +
                        "WHERE e.subject_id = ? AND u.role = 'student'";

        String sqlStatus =
                "SELECT student_id, name, NVL(status, '결석') AS status FROM (" +
                        "  SELECT u.user_id AS student_id, u.name, ar.status, " +
                        "         ROW_NUMBER() OVER (PARTITION BY u.user_id ORDER BY ar.attendance_datetime DESC) AS rn " +
                        "  FROM enrollments e " +
                        "  JOIN users u ON u.user_id = e.student_id " +
                        "  LEFT JOIN attendance_records ar " +
                        "    ON ar.student_id = e.student_id " +
                        "   AND ar.subject_id = e.subject_id " +
                        "   AND TRUNC(ar.attendance_datetime) = TRUNC(SYSDATE) " +
                        "  WHERE e.subject_id = ? AND u.role = 'student'" +
                        ") WHERE rn = 1";

        try (Connection conn = getConnection();
             PreparedStatement pstmtAll = conn.prepareStatement(sqlAll);
             PreparedStatement pstmtStatus = conn.prepareStatement(sqlStatus)) {

            // 전체 학생 리스트 가져오기
            pstmtAll.setString(1, subjectId);
            ResultSet rsAll = pstmtAll.executeQuery();
            JsonArray allStudents = new JsonArray();
            while (rsAll.next()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("studentId", rsAll.getString("student_id"));
                obj.addProperty("name", rsAll.getString("name"));
                allStudents.add(obj);
            }

            // 출석/결석 리스트 분류
            pstmtStatus.setString(1, subjectId);
            ResultSet rs = pstmtStatus.executeQuery();

            JsonArray present = new JsonArray();
            JsonArray absent = new JsonArray();
            JsonArray unknown = new JsonArray();

            while (rs.next()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("studentId", rs.getString("student_id"));
                obj.addProperty("name", rs.getString("name"));
                obj.addProperty("status", rs.getString("status"));

                String status = rs.getString("status");
                if ("출석".equals(status)) {
                    present.add(obj);
                } else if ("결석".equals(status)) {
                    absent.add(obj);
                } else {
                    unknown.add(obj);
                }
            }

            JsonObject root = new JsonObject();
            root.add("allStudents", allStudents);
            root.add("presentStudents", present);
            root.add("absentStudents", absent);
            root.add("unknownStudents", unknown);

            String jsonResult = new Gson().toJson(root);
            response.getWriter().print(jsonResult);

        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(500);
            response.getWriter().write("{\"error\":\"서버 오류\"}");
        }
    }
}
