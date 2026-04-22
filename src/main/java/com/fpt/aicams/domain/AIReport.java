package com.fpt.aicams.domain;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "ai_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AIReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prompt_id")
    private AIPrompt prompt;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "contribution_analysis", columnDefinition = "TEXT")
    private String contributionAnalysis;

    @Column(name = "is_approved")
    private Boolean isApproved;
}
