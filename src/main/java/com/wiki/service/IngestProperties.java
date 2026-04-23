package com.wiki.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "wiki.ingest")
public class IngestProperties {

    private Classifier classifier = new Classifier();
    private QualityGate qualityGate = new QualityGate();

    public Classifier getClassifier() { return classifier; }
    public void setClassifier(Classifier classifier) { this.classifier = classifier; }

    public QualityGate getQualityGate() { return qualityGate; }
    public void setQualityGate(QualityGate qualityGate) { this.qualityGate = qualityGate; }

    public static class Classifier {
        private boolean enabled = true;
        private List<String> knownTypes = new ArrayList<>(List.of("generic"));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getKnownTypes() { return knownTypes; }
        public void setKnownTypes(List<String> knownTypes) { this.knownTypes = knownTypes; }
    }

    public static class QualityGate {
        private boolean enabled = true;
        private Map<String, List<String>> typeGates = new LinkedHashMap<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Map<String, List<String>> getTypeGates() { return typeGates; }
        public void setTypeGates(Map<String, List<String>> typeGates) { this.typeGates = typeGates; }
    }
}
