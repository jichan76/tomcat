package com.example.attendance.servlet;

import com.example.attendance.repository.InMemoryDB;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;

@WebServlet("/startAttendance")
public class StartAttendanceServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String subjectIdStr = req.getParameter("subjectId");
        int subjectId = subjectIdStr == null ? 0 : Integer.parseInt(subjectIdStr);
        // 실제 로직: BSSID 저장 등. 여기선 단순화.
        // 출석한 학생을 초기화(없음)
        InMemoryDB.ATTENDANCE.put(subjectId, new java.util.HashSet<>());

        resp.setContentType("application/json; charset=UTF-8");
        resp.getWriter().write("{\"result\":\"started\"}");
    }
}
