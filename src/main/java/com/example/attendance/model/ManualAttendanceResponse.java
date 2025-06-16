package com.example.attendance.model;

import java.util.List;

public class ManualAttendanceResponse {
    private List<StudentAttendance> studentList;

    public ManualAttendanceResponse(List<StudentAttendance> studentList) {
        this.studentList = studentList;
    }

    public List<StudentAttendance> getStudentList() {
        return studentList;
    }
}
