package com.fpt.aicams.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.aicams.core.api.GithubApiClient;
import com.fpt.aicams.core.enums.CommitType;
import com.fpt.aicams.core.enums.TaskStatus;
import com.fpt.aicams.domain.*;
import com.fpt.aicams.dto.commit.GithubCommitResponse;
import com.fpt.aicams.dto.webhook.GithubPullRequestPayload;
import com.fpt.aicams.dto.webhook.GithubPushPayload;
import com.fpt.aicams.repository.*;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log4j2
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class GithubWebhookService {

    ObjectMapper objectMapper;
    CommitRepository commitRepository;
    PullRequestRepository pullRequestRepository;
    TaskRepository taskRepository;
    GithubRepoRepository githubRepoRepository;
    GithubAccountRepository githubAccountRepository;
    StudentRepository studentRepository;
    PullRequestService pullRequestService;
    GithubApiClient githubApiClient;
    TaskService taskService;
    GithubAccountService githubAccountService;

    // Regex bóc tách mã Task (VD: AICAMS-51) từ TÊN PULL REQUEST
    private static final Pattern JIRA_KEY_PATTERN = Pattern.compile("([A-Z+\\d]+-\\d+)");

    @Transactional
    public void processEvent(String eventType, String payload) {
        try {
            if ("push".equals(eventType)) {
                GithubPushPayload pushData = objectMapper.readValue(payload, GithubPushPayload.class);
                handlePushEvent(pushData);
            } else if ("pull_request".equals(eventType)) {
                GithubPullRequestPayload prData = objectMapper.readValue(payload, GithubPullRequestPayload.class);
                handlePullRequestEvent(prData);
            }
        } catch (Exception e) {
            log.error("Lỗi khi xử lý Payload từ Queue: {}", e.getMessage(), e);
            throw new RuntimeException("Lỗi khi xử lý Payload từ Queue", e);
        }
    }

    private void handlePushEvent(GithubPushPayload payload) {
        String repoName = payload.getRepository().getName();
        String ownerName = payload.getRepository().getOwner().getLogin();

        GithubRepo repo = githubRepoRepository.findByRepoNameAndOwnerName(repoName, ownerName).orElse(null);
        if (repo == null) return;

        String branchName = payload.getRef().replace("refs/heads/", "");
        String senderAccountId = String.valueOf(payload.getSender().getId());
        Student author = studentRepository.findByGithubAccounts_AccountId(senderAccountId).orElse(null);

        if (payload.getCommits() != null) {
            for (GithubPushPayload.CommitData commitData : payload.getCommits()) {

//                int linesAdded = (commitData.getAdded() != null ? commitData.getAdded().size() : 0) +
//                        (commitData.getModified() != null ? commitData.getModified().size() : 0);
//                int linesRemoved = commitData.getRemoved() != null ? commitData.getRemoved().size() : 0;

                // 2. GỌI API GITHUB ĐỂ LẤY STATS (SỐ DÒNG CODE)
                int linesAdded = 0;
                int linesRemoved = 0;
//                String accessToken = githubAccountRepository.findAccessTokensByProjectId(
//                        repo.getProject().getId()).stream().findFirst().orElse(null);
                List<GithubAccount> ghAccounts = githubAccountRepository.findAccessTokensByProjectId(repo.getProject().getId());
                String accessToken = null;
                for (GithubAccount ghAccount : ghAccounts) {
                    try {
                        String token = githubAccountService.getValidAccessToken(ghAccount.getId());
                        if (token != null && !token.isEmpty()) {
                            accessToken = token;
                            break;
                        }
                    } catch (Exception e) {
                        log.warn("Không thể lấy access token cho GithubAccount ID: {}. Thử tài khoản khác...", ghAccount.getId());
                    }
                }
                try {
                    if (accessToken != null && !accessToken.isEmpty()) {
                        GithubCommitResponse commitDetail = githubApiClient.getCommitDetail(
                                ownerName, repoName, commitData.getId(), accessToken
                        );

                        if (commitDetail != null && commitDetail.getStats() != null) {
                            linesAdded = commitDetail.getStats().getAdditions() != null ? commitDetail.getStats().getAdditions() : 0;
                            linesRemoved = commitDetail.getStats().getDeletions() != null ? commitDetail.getStats().getDeletions() : 0;
                        }
                    } else {
                        log.warn("Không có Access Token để gọi API lấy số dòng code cho commit {}",
                                commitData.getId());
                    }
                } catch (Exception e) {
                    log.error("Lỗi khi lấy chi tiết commit {} từ Github API: {}",
                            commitData.getId(), e.getMessage());
                }

                // TÌM XEM COMMIT NÀY ĐÃ TỒN TẠI CHƯA
                Commit existingCommit = commitRepository.findByCommitHash(commitData.getId()).orElse(null);

                if (existingCommit != null) {
                    // Nếu là Commit Ảo do PR tạo ra (chưa có message), ta sẽ CẬP NHẬT (Điền nốt thông tin)
                    if (existingCommit.getCommitMessage() == null) {
                        existingCommit.setCommitMessage(commitData.getMessage());
                        existingCommit.setCommitUrl(commitData.getUrl());
                        existingCommit.setLinesAdded(linesAdded);
                        existingCommit.setLinesRemoved(linesRemoved);
                        existingCommit.setBranch(branchName);
                        existingCommit.setAuthor(author);
                        existingCommit.setCommittedAt(LocalDateTime.now());

                        commitRepository.save(existingCommit);
                        log.info("Cập nhật hoàn chỉnh thông tin cho Commit MERGE ảo: {}", commitData.getId());
                    }
                    continue;
                }

                // NẾU CHƯA TỒN TẠI -> TẠO MỚI (Lưu thuần túy, không xét Task)
                String commitMessage = commitData.getMessage();
                String commitType = CommitType.COMMIT.name();
                if (commitMessage.startsWith("Merge pull request") || commitMessage.startsWith(
                        "Merge branch")) {
                    commitType = CommitType.MERGE.name();
                }

                Commit newCommit = Commit.builder()
                        .commitHash(commitData.getId())
                        .commitUrl(commitData.getUrl())
                        .commitMessage(commitMessage)
                        .linesAdded(linesAdded)
                        .linesRemoved(linesRemoved)
                        .branch(branchName)
                        .type(commitType)
                        .githubRepo(repo)
                        .author(author)
                        .committedAt(LocalDateTime.now())
                        .build();

                commitRepository.save(newCommit);
                log.info("Đã lưu commit mới qua Webhook: {}", commitData.getId());
            }
        }
    }

//    private void handlePullRequestEvent(GithubPullRequestPayload payload) {
//        String repoName = payload.getRepository().getName();
//        String ownerName = payload.getRepository().getOwner().getLogin();
//
//        GithubRepo repo = githubRepoRepository.findByRepoNameAndOwnerName(repoName, ownerName).orElse(null);
//        if (repo == null) return;
//
//        GithubPullRequestPayload.PullRequestData prData = payload.getPull_request();
//
//        // TÌM TASK DỰA VÀO TIÊU ĐỀ PULL REQUEST
//        Task matchedTask = findTaskByPrTitle(prData.getTitle(), repo.getProject().getId());
//
//        // KIỂM TRA ĐỊNH DẠNG (FORMAT VALIDATION)
//        List<String> violations = new ArrayList<>();
//        if (matchedTask == null) {
//            violations.add(
//                    "Tiêu đề PR không chứa mã Task hợp lệ (VD: AICAMS-51) hoặc Task không tồn tại trong dự án.");
//        }
//
//        String titlePattern = "^(feat|fix|wip)/[a-zA-Z][a-zA-Z+0-9]*-\\d+:\\s+.*$";
//
//        if (!prData.getTitle().matches(titlePattern)) {
//            violations.add(
//                    "Tiêu đề PR sai định dạng. Định dạng chuẩn phải là: <b>loại/MÃ-TASK: nội dung</b>" +
//                            "(VD: feat/AICAMS-2: Thêm chức năng mới). Các loại cho phép: feat, fix.");
//        }
//
//        String authorAccountId = String.valueOf(prData.getUser().getId());
//        Student author = studentRepository.findByGithubAccounts_AccountId(authorAccountId).orElse(null);
//
//        PullRequest existingPr = pullRequestRepository.findByPrNumberAndGithubRepo_Id(payload.getNumber(), repo.getId()).orElse(null);
//        PullRequest currentPr;
//
//        boolean isMerged = prData.getMerged() != null && prData.getMerged();
//        boolean isClosedWithoutMerge = "closed".equalsIgnoreCase(prData.getState()) && !isMerged;
//
//        // Xác định loại workflow:
//        // - MERGE: PR đã được merge thành công
//        // - CLOSED_WITHOUT_MERGE: PR bị close mà không merge (KHÔNG tính là hoàn thành)
//        // - PR: PR đang mở (open)
//        String workflowType;
//        if (isMerged) {
//            workflowType = CommitType.MERGE.name();
//        } else if (isClosedWithoutMerge) {
//            workflowType = CommitType.CLOSED_WITHOUT_MERGE.toString();
//        } else {
//            workflowType = "PR";
//        }
//
//        if (existingPr == null) {
//            PullRequest newPr = PullRequest.builder()
//                    .prNumber(payload.getNumber())
//                    .title(prData.getTitle())
//                    .state(prData.getState())
//                    .prUrl(prData.getUrl())
//                    .sourceBranch(prData.getHead() != null ? prData.getHead().getRef() : null)
//                    .targetBranch(prData.getBase() != null ? prData.getBase().getRef() : null)
//                    .createdAt(LocalDateTime.now())
//                    .merged(isMerged)
//                    .mergedAt(isMerged ? LocalDateTime.now() : null)
//                    .githubRepo(repo)
//                    .author(author)
//                    .task(matchedTask)
//                    .isFormattingValid(violations.isEmpty())
//                    .formattingViolations(
//                            violations.isEmpty() ? null : String.join(", ", violations))
//                    .build();
//
//            currentPr = pullRequestRepository.save(newPr);
//            log.info("Đã tạo mới PR #{} từ Webhook (Valid: {}, Merged: {})", payload.getNumber(),
//                    violations.isEmpty(), isMerged);
//
//            if (violations.isEmpty()) {
//                if (matchedTask != null) {
//                    updateTaskStatusBasedOnWorkflow(matchedTask, prData.getTitle(), workflowType);
//                }
//                // Chỉ gửi notify khi PR đang open (không notify cho PR đã close)
//                if ("open".equalsIgnoreCase(prData.getState())) {
//                    pullRequestService.notifyNewPr(currentPr);
//                }
//            } else {
//                if ("open".equalsIgnoreCase(prData.getState())) {
//                    pullRequestService.notifyInvalidPrFormat(currentPr);
//                }
//            }
//
//        } else {
//            boolean wasValid = existingPr.getIsFormattingValid() != null && existingPr.getIsFormattingValid();
//            boolean isNowValid = violations.isEmpty();
//
//            existingPr.setTitle(prData.getTitle());
//            existingPr.setState(prData.getState());
//            existingPr.setSourceBranch(
//                    prData.getHead() != null ? prData.getHead().getRef() : existingPr.getSourceBranch());
//            existingPr.setTargetBranch(
//                    prData.getBase() != null ? prData.getBase().getRef() : existingPr.getTargetBranch());
//            existingPr.setIsFormattingValid(isNowValid);
//            existingPr.setFormattingViolations(isNowValid ? null : String.join(", ", violations));
//            existingPr.setTask(matchedTask);
//
//            if (isMerged) {
//                existingPr.setMerged(true);
//                existingPr.setMergedAt(LocalDateTime.now());
//            }
//
//            currentPr = pullRequestRepository.save(existingPr);
//            log.info("Đã cập nhật PR #{} từ Webhook (Valid: {}, Merged: {})", payload.getNumber(), isNowValid, isMerged);
//
//            if (isNowValid) {
//                if (matchedTask != null) {
//                    updateTaskStatusBasedOnWorkflow(matchedTask, prData.getTitle(), workflowType);
//                }
//                // Nếu trước đó sai mà giờ đúng, hoặc là PR mới đúng ngay từ đầu (đã handle ở if trên)
//                // Ở đây là update, nếu trước đó sai mà giờ đúng thì notify Team
//                if (!wasValid && "open".equalsIgnoreCase(prData.getState())) {
//                    pullRequestService.notifyNewPr(currentPr);
//                }
//            } else {
//                // Chỉ gửi email thông báo lỗi nếu trước đó PR này hợp lệ (wasValid = true)
//                // Từ hợp lệ -> không hợp lệ thì mới gửi thông báo.
//                // Nếu trước đó đã không hợp lệ rồi (wasValid = false) thì không gửi lại để tránh spam.
//                if (wasValid && "open".equalsIgnoreCase(prData.getState())) {
//                    pullRequestService.notifyInvalidPrFormat(currentPr);
//                }
//            }
//        }
//
//        // ==============================================================
//        // Xử lý Squash Merge Commit (Nối vào PR)
//        // ==============================================================
//        if (isMerged && prData.getMerge_commit_sha() != null) {
//            Commit squashMergeCommit = commitRepository.findByCommitHash(prData.getMerge_commit_sha()).orElse(null);
//
//            if (squashMergeCommit != null) {
//                // Trường hợp 1: Luồng Push đã chạy trước -> Gán PR id và Update Type
//                if (!CommitType.MERGE.name().equals(squashMergeCommit.getType())) {
//                    squashMergeCommit.setType(CommitType.MERGE.name());
//                }
//                squashMergeCommit.setPullRequest(currentPr);
//                commitRepository.save(squashMergeCommit);
//                log.info("Chốt sổ ngay lập tức: Đã nối Commit {} vào PR #{}", squashMergeCommit.getCommitHash(), currentPr.getPrNumber());
//            } else {
//                // Trường hợp 2: Luồng Push đang bị chậm. Ta TẠO LUÔN MỘT COMMIT ẢO CHỈ CHỨA PR ID!
//                Commit placeholderCommit = Commit.builder()
//                        .commitHash(prData.getMerge_commit_sha())
//                        .type(CommitType.MERGE.name())
//                        .pullRequest(currentPr)
//                        .githubRepo(repo)
//                        .build();
//
//                commitRepository.save(placeholderCommit);
//                log.info("Tạo Commit Ảo: Luồng PR chạy nhanh hơn Push. Đã tạo sẵn vỏ bọc MERGE cho commit {}", prData.getMerge_commit_sha());
//            }
//
//            updateTaskStatusBasedOnWorkflow(currentPr.getTask(), prData.getTitle(),
//                    CommitType.MERGE.name());
//        }
//    }

    private void handlePullRequestEvent(GithubPullRequestPayload payload) {
        String repoName = payload.getRepository().getName();
        String ownerName = payload.getRepository().getOwner().getLogin();

        GithubRepo repo = githubRepoRepository.findByRepoNameAndOwnerName(repoName, ownerName).orElse(null);
        if (repo == null) return;

        GithubPullRequestPayload.PullRequestData prData = payload.getPull_request();

        // TÌM EXISTING PR TRƯỚC ĐỂ LẤY DỮ LIỆU MAP THỦ CÔNG (NẾU CÓ)
        PullRequest existingPr = pullRequestRepository.findByPrNumberAndGithubRepo_Id(payload.getNumber(), repo.getId()).orElse(null);

        // 1. TÌM TASK TỪ TIÊU ĐỀ WEBHOOK
        Task parsedTask = findTaskByPrTitle(prData.getTitle(), repo.getProject().getId());
        Task matchedTask;
        boolean isManuallyMapped = false; // Cờ đánh dấu PR đã được gắn Task thủ công qua API

        // LOGIC GIỮ LẠI TASK GẮN THỦ CÔNG:
        if (parsedTask != null) {
            matchedTask = parsedTask; // Tiêu đề Github chuẩn -> Lấy theo Github
        } else if (existingPr != null && existingPr.getTask() != null) {
            matchedTask = existingPr.getTask(); // Tiêu đề Github sai nhưng DB đã có -> Dùng Task trong DB
            isManuallyMapped = true;
        } else {
            matchedTask = null; // Cả Github và DB đều không có
        }

        // 2. KIỂM TRA ĐỊNH DẠNG (FORMAT VALIDATION)
        List<String> violations = new ArrayList<>();
        if (matchedTask == null) {
            violations.add("Tiêu đề PR không chứa mã Task hợp lệ (VD: AICAMS-51) hoặc Task không tồn tại trong dự án.");
        }

        String titlePattern = "^(feat|fix|wip)/[a-zA-Z][a-zA-Z+0-9]*-\\d+:\\s+.*$";

        // CHÚ Ý: Nếu PR đã được gắn Task thủ công qua API, ta bỏ qua check regex định dạng tiêu đề
        if (!isManuallyMapped && !prData.getTitle().matches(titlePattern)) {
            violations.add("Tiêu đề PR sai định dạng. Định dạng chuẩn phải là: <b>loại/MÃ-TASK: nội dung</b>(VD: feat/AICAMS-2: Thêm chức năng mới). Các loại cho phép: feat, fix.");
        }

        String authorAccountId = String.valueOf(prData.getUser().getId());
        Student author = studentRepository.findByGithubAccounts_AccountId(authorAccountId).orElse(null);

        boolean isMerged = prData.getMerged() != null && prData.getMerged();
        boolean isClosedWithoutMerge = "closed".equalsIgnoreCase(prData.getState()) && !isMerged;

        String workflowType;
        if (isMerged) {
            workflowType = CommitType.MERGE.name();
        } else if (isClosedWithoutMerge) {
            workflowType = CommitType.CLOSED_WITHOUT_MERGE.toString();
        } else {
            workflowType = "PR";
        }

        PullRequest currentPr;

        if (existingPr == null) {
            Boolean autoApproved = null;
            if (repo.getProject() != null && repo.getProject().getAutoPrApproval() != null && repo.getProject().getAutoPrApproval()) {
                autoApproved = true;
            }

            PullRequest newPr = PullRequest.builder()
                    .prNumber(payload.getNumber())
                    .title(prData.getTitle())
                    .state(prData.getState())
                    .prUrl(prData.getUrl())
                    .sourceBranch(prData.getHead() != null ? prData.getHead().getRef() : null)
                    .targetBranch(prData.getBase() != null ? prData.getBase().getRef() : null)
                    .createdAt(LocalDateTime.now())
                    .merged(isMerged)
                    .mergedAt(isMerged ? LocalDateTime.now() : null)
                    .githubRepo(repo)
                    .author(author)
                    .task(matchedTask)
                    .isFormattingValid(violations.isEmpty())
                    .formattingViolations(violations.isEmpty() ? null : String.join(", ", violations))
                    .isApproved(autoApproved)
                    .build();

            currentPr = pullRequestRepository.save(newPr);
            log.info("Đã tạo mới PR #{} từ Webhook (Valid: {}, Merged: {})", payload.getNumber(), violations.isEmpty(), isMerged);

            if (violations.isEmpty()) {
                if (matchedTask != null) {
                    updateTaskStatusBasedOnWorkflow(matchedTask, prData.getTitle(), workflowType, isManuallyMapped);
                }
                if ("open".equalsIgnoreCase(prData.getState())) {
                    pullRequestService.notifyNewPr(currentPr);
                }
            } else {
                if ("open".equalsIgnoreCase(prData.getState())) {
                    pullRequestService.notifyInvalidPrFormat(currentPr);
                }
            }

        } else {
            boolean wasValid = existingPr.getIsFormattingValid() != null && existingPr.getIsFormattingValid();
            boolean isNowValid = violations.isEmpty();

            existingPr.setTitle(prData.getTitle());
            existingPr.setState(prData.getState());
            existingPr.setSourceBranch(prData.getHead() != null ? prData.getHead().getRef() : existingPr.getSourceBranch());
            existingPr.setTargetBranch(prData.getBase() != null ? prData.getBase().getRef() : existingPr.getTargetBranch());
            existingPr.setIsFormattingValid(isNowValid);
            existingPr.setFormattingViolations(isNowValid ? null : String.join(", ", violations));
            existingPr.setTask(matchedTask); // Lúc này matchedTask sẽ không bị null nếu đã gắn qua API

            if (isMerged) {
                existingPr.setMerged(true);
                existingPr.setMergedAt(LocalDateTime.now());
            }

            currentPr = pullRequestRepository.save(existingPr);
            log.info("Đã cập nhật PR #{} từ Webhook (Valid: {}, Merged: {})", payload.getNumber(), isNowValid, isMerged);

            if (isNowValid) {
                if (matchedTask != null) {
                    updateTaskStatusBasedOnWorkflow(matchedTask, prData.getTitle(), workflowType, isManuallyMapped);
                }
                if (!wasValid && "open".equalsIgnoreCase(prData.getState())) {
                    pullRequestService.notifyNewPr(currentPr);
                }
            } else {
                if (wasValid && "open".equalsIgnoreCase(prData.getState())) {
                    pullRequestService.notifyInvalidPrFormat(currentPr);
                }
            }
        }

        // ==============================================================
        // Xử lý Squash Merge Commit (Nối vào PR)
        // ==============================================================
        if (isMerged && prData.getMerge_commit_sha() != null) {
            Commit squashMergeCommit = commitRepository.findByCommitHash(prData.getMerge_commit_sha()).orElse(null);

            if (squashMergeCommit != null) {
                if (!CommitType.MERGE.name().equals(squashMergeCommit.getType())) {
                    squashMergeCommit.setType(CommitType.MERGE.name());
                }
                squashMergeCommit.setPullRequest(currentPr);
                commitRepository.save(squashMergeCommit);
                log.info("Chốt sổ ngay lập tức: Đã nối Commit {} vào PR #{}", squashMergeCommit.getCommitHash(), currentPr.getPrNumber());
            } else {
                Commit placeholderCommit = Commit.builder()
                        .commitHash(prData.getMerge_commit_sha())
                        .type(CommitType.MERGE.name())
                        .pullRequest(currentPr)
                        .githubRepo(repo)
                        .build();

                commitRepository.save(placeholderCommit);
                log.info("Tạo Commit Ảo: Luồng PR chạy nhanh hơn Push. Đã tạo sẵn vỏ bọc MERGE cho commit {}", prData.getMerge_commit_sha());
            }

            updateTaskStatusBasedOnWorkflow(currentPr.getTask(), prData.getTitle(), CommitType.MERGE.name(), isManuallyMapped);

            // KIỂM TRA NẾU ĐÃ MERGE NHƯNG CHƯA ĐƯỢC APPROVE -> GỬI CẢNH BÁO
            if (currentPr.getIsApproved() == null || !currentPr.getIsApproved()) {
                pullRequestService.notifyUnapprovedPrMerged(currentPr);
            }
        }
    }

    private void updateTaskStatusBasedOnWorkflow(Task task, String prTitle, String type, boolean isManuallyMapped) {
        if (task == null || prTitle == null) return;

        // Bỏ qua check tiền tố feat/fix/wip NẾU Task đã được gán thủ công thành công qua API
        if (!isManuallyMapped) {
            String lowerTitle = prTitle.toLowerCase();
            boolean isFeatOrFix = lowerTitle.startsWith("feat") || lowerTitle.startsWith("fix");
            boolean isWip = lowerTitle.startsWith("wip");

            // Format chỉ được xét trên tiêu đề PR (PR Title)
            if (!isFeatOrFix && !isWip) return;
        }

        boolean hasOpenPr = pullRequestRepository.existsByTask_IdAndStateIgnoreCase(task.getId(), "open");
        String currentStatus = task.getStatus() != null ? task.getStatus() : "";
        String newStatus = currentStatus;

        if (CommitType.MERGE.name().equals(type) && !TaskStatus.DONE.name().equalsIgnoreCase(currentStatus)) {
            // CHỈ set DONE khi PR thực sự đã MERGE (type = MERGE)
            newStatus = TaskStatus.DONE.name();
        } else if (CommitType.CLOSED_WITHOUT_MERGE.toString().equals(type)) {
            // PR bị close mà KHÔNG merge -> Hạ cấp task (KHÔNG tính là hoàn thành)
            if (!TaskStatus.DONE.name().equalsIgnoreCase(currentStatus)
                    && !TaskStatus.CANCELLED.name().equalsIgnoreCase(currentStatus)) {
                // Kiểm tra xem còn PR open nào khác cho task này không
                if (hasOpenPr) {
                    newStatus = TaskStatus.IN_REVIEW.name();
                } else {
                    newStatus = TaskStatus.IN_PROGRESS.name();
                }
            }
        } else if ("PR".equals(type) && hasOpenPr) {
            newStatus = TaskStatus.IN_REVIEW.name();
        } else {
            // Hạ cấp nếu không có PR nào mở và chưa done/cancel
            if (!TaskStatus.DONE.name().equalsIgnoreCase(currentStatus)
                    && !TaskStatus.CANCELLED.name().equalsIgnoreCase(currentStatus)) {
                newStatus = TaskStatus.IN_PROGRESS.name();
            }
        }

        if (!newStatus.equals(currentStatus)) {
            task.setStatus(newStatus);
            if (TaskStatus.DONE.name().equals(newStatus)) {
                task.setCompletedAt(LocalDateTime.now());
            }
            taskRepository.save(task);

            if (CommitType.CLOSED_WITHOUT_MERGE.toString().equals(type)) {
                log.info("PR bị close KHÔNG merge -> Hạ cấp Task {} từ {} về {} (PR: {})",
                        task.getKey(), currentStatus, newStatus, prTitle);
            } else if (TaskStatus.IN_PROGRESS.name().equals(newStatus) && TaskStatus.IN_REVIEW.name().equals(currentStatus)) {
                log.info("Hạ cấp Task {} từ REVIEW về IN_PROGRESS (Do PR bị đóng/hủy).", task.getKey());
            } else {
                log.info("Chuyển trạng thái Task {} thành: {} (Dựa trên Pull Request: {} - Loại: {})",
                        task.getKey(), newStatus, prTitle, type);
            }
            if (task.getParentTask() != null) {
                taskService.handleAutoStatusUpdateForParent(task.getParentTask());
            }
            taskService.triggerJiraSyncIfHasKey(task);
        }
    }

    private Task findTaskByPrTitle(String title, UUID projectId) {
        if (title == null || title.trim().isEmpty()) return null;
        Matcher matcher = JIRA_KEY_PATTERN.matcher(title.toUpperCase());
        if (matcher.find()) {
            String taskKey = matcher.group(1);
            return taskRepository.findByKeyAndProject_Id(taskKey, projectId).orElse(null);
        }
        return null;
    }

    private void updateTaskStatusBasedOnWorkflow(Task task, String prTitle, String type) {
        if (task == null || prTitle == null) return;

        String lowerTitle = prTitle.toLowerCase();
        boolean isFeatOrFix = lowerTitle.startsWith("feat") || lowerTitle.startsWith("fix");
        boolean isWip = lowerTitle.startsWith("wip");

        // Format chỉ được xét trên tiêu đề PR (PR Title)
        if (!isFeatOrFix && !isWip) return;

        boolean hasOpenPr = pullRequestRepository.existsByTask_IdAndStateIgnoreCase(task.getId(), "open");
        String currentStatus = task.getStatus() != null ? task.getStatus() : "";
        String newStatus = currentStatus;

        if (CommitType.MERGE.name().equals(type) && !TaskStatus.DONE.name().equalsIgnoreCase(currentStatus)) {
            // CHỈ set DONE khi PR thực sự đã MERGE (type = MERGE)
            newStatus = TaskStatus.DONE.name();
        } else if (CommitType.CLOSED_WITHOUT_MERGE.toString().equals(type)) {
            // PR bị close mà KHÔNG merge -> Hạ cấp task (KHÔNG tính là hoàn thành)
            if (!TaskStatus.DONE.name().equalsIgnoreCase(currentStatus)
                    && !TaskStatus.CANCELLED.name().equalsIgnoreCase(currentStatus)) {
                // Kiểm tra xem còn PR open nào khác cho task này không
                if (hasOpenPr) {
                    newStatus = TaskStatus.IN_REVIEW.name();
                } else {
                    newStatus = TaskStatus.IN_PROGRESS.name();
                }
            }
        } else if ("PR".equals(type) && hasOpenPr) {
            newStatus = TaskStatus.IN_REVIEW.name();
        } else {
            // Hạ cấp nếu không có PR nào mở và chưa done/cancel
            if (!TaskStatus.DONE.name().equalsIgnoreCase(
                    currentStatus) && !TaskStatus.CANCELLED.name().equalsIgnoreCase(
                    currentStatus)) {
                newStatus = TaskStatus.IN_PROGRESS.name();
            }
        }

        if (!newStatus.equals(currentStatus)) {
            task.setStatus(newStatus);
            if (TaskStatus.DONE.name().equals(newStatus)) {
                task.setCompletedAt(LocalDateTime.now());
            }
            taskRepository.save(task);

            if (CommitType.CLOSED_WITHOUT_MERGE.toString().equals(type)) {
                log.info("PR bị close KHÔNG merge -> Hạ cấp Task {} từ {} về {} (PR: {})",
                        task.getKey(), currentStatus, newStatus, prTitle);
            } else if (TaskStatus.IN_PROGRESS.name().equals(newStatus) && TaskStatus.IN_REVIEW.name().equals(currentStatus)) {
                log.info("Hạ cấp Task {} từ REVIEW về IN_PROGRESS (Do PR bị đóng/hủy).", task.getKey());
            } else {
                log.info(
                        "Chuyển trạng thái Task {} thành: {} (Dựa trên Pull Request: {} - Loại: {})",
                        task.getKey(), newStatus, prTitle, type);
            }
            if (task.getParentTask() != null) {
                taskService.handleAutoStatusUpdateForParent(task.getParentTask());
            }
            taskService.triggerJiraSyncIfHasKey(task);
        }
    }
}