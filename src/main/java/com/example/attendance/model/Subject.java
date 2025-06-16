package com.example.attendance.model;

public class Subject {
    private int id;
    private String name;
    private String bssid;

    public Subject(int id, String name, String bssid) {
        this.id = id;
        this.name = name;
        this.bssid = bssid;
    }
    public int getId() { return id; }
    public String getName() { return name; }
    public String getBssid(){ return bssid; }
    public void setBssid(String bssid) { this.bssid = bssid; }
}
