package com.example.attendance.servlet;

import org.json.JSONObject;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

@WebServlet("/currentOpen")
public class CurrentOpenServlet extends HttpServlet {
    private static final Logger logger = Logger.getLogger(CurrentOpenServlet.class.getName());
    /** ✅ DB 연결 설정 */
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
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        resp.setContentType("application/json;charset=utf-8");
        resp.setCharacterEncoding("UTF-8");

        JSONObject json = new JSONObject();

        try (PrintWriter out = resp.getWriter()) {
            String sid = req.getParameter("subjectId");
            if (sid == null || sid.isEmpty()) {
                resp.setStatus(400);
                json.put("error", "subjectId parameter is required");
                out.print(json.toString());
                return;
            }

            int subjectId = Integer.parseInt(sid);

            try (Connection conn = getConnection()) {

                // ✅ PROFESSOR_BSSID가 null인지 확인
                String sql = "SELECT PROFESSOR_BSSID FROM SUBJECTS WHERE SUBJECT_ID = ?";

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setInt(1, subjectId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String bssid = rs.getString("PROFESSOR_BSSID");

                            // null이면 true, 값 있으면 false
                            boolean isOpen = (bssid == null || bssid.isEmpty());
                            logger.info("과목아이디=" + subjectId + " → isOpen=" + isOpen + ", bssid=" + bssid);

                            json.put("open", isOpen);
                            json.put("bssid", bssid == null ? JSONObject.NULL : bssid);
                        } else {
                            // 과목 자체가 없을 경우 open=false
                            json.put("open", false);
                            json.put("bssid", JSONObject.NULL);
                        }
                    }
                }
            }

            out.print(json.toString());

        } catch (Exception e) {
            e.printStackTrace();
            resp.setStatus(500);
            try (PrintWriter out = resp.getWriter()) {
                json.put("error", e.getMessage());
                out.print(json.toString());
            }
        }
    }
}
