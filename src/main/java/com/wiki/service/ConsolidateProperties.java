package com.wiki.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "wiki.consolidate")
public class ConsolidateProperties {
    private boolean enabled = true;
    private double cosineFloor = 0.40;
    private int neighborsPerPage = 3;
    private int candidatesFromQmd = 15;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public double getCosineFloor() { return cosineFloor; }
    public void setCosineFloor(double cosineFloor) { this.cosineFloor = cosineFloor; }

    public int getNeighborsPerPage() { return neighborsPerPage; }
    public void setNeighborsPerPage(int neighborsPerPage) { this.neighborsPerPage = neighborsPerPage; }

    public int getCandidatesFromQmd() { return candidatesFromQmd; }
    public void setCandidatesFromQmd(int candidatesFromQmd) { this.candidatesFromQmd = candidatesFromQmd; }
}
