package com.example.attendance.model;

public class StudentAttendance {
    private String studentId;
    private String name;
    private String status;

    public StudentAttendance(String studentId, String name, String status) {
        this.studentId = studentId;
        this.name = name;
        this.status = status;
    }

    public String getStudentId() { return studentId; }
    public String getName() { return name; }
    public String getStatus() { return status; }
    public void setStudentId(String studentId) { this.studentId = studentId; }
    public void setName(String name) { this.name = name; }
    public void setStatus(String status) { this.status = status; }
}
