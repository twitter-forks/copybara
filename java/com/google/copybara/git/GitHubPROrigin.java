/*
 * Copyright (C) 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.git;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.copybara.exception.ValidationException.checkCondition;
import static com.google.copybara.git.github.util.GitHubUtil.asHeadRef;
import static com.google.copybara.git.github.util.GitHubUtil.asMergeRef;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.copybara.BaselinesWithoutLabelVisitor;
import com.google.copybara.Change;
import com.google.copybara.Endpoint;
import com.google.copybara.GeneralOptions;
import com.google.copybara.Origin;
import com.google.copybara.authoring.Authoring;
import com.google.copybara.checks.Checker;
import com.google.copybara.exception.CannotResolveRevisionException;
import com.google.copybara.exception.EmptyChangeException;
import com.google.copybara.exception.RepoException;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.git.GitOrigin.ReaderImpl;
import com.google.copybara.git.GitOrigin.SubmoduleStrategy;
import com.google.copybara.git.GitRepository.GitLogEntry;
import com.google.copybara.git.github.api.AuthorAssociation;
import com.google.copybara.git.github.api.CheckRun;
import com.google.copybara.git.github.api.CheckRuns;
import com.google.copybara.git.github.api.CombinedStatus;
import com.google.copybara.git.github.api.GitHubApi;
import com.google.copybara.git.github.api.Issue;
import com.google.copybara.git.github.api.Label;
import com.google.copybara.git.github.api.PullRequest;
import com.google.copybara.git.github.api.Review;
import com.google.copybara.git.github.api.Status;
import com.google.copybara.git.github.api.Status.State;
import com.google.copybara.git.github.api.User;
import com.google.copybara.git.github.util.GitHubHost;
import com.google.copybara.git.github.util.GitHubHost.GitHubPrUrl;
import com.google.copybara.git.github.util.GitHubUtil;
import com.google.copybara.profiler.Profiler.ProfilerTask;
import com.google.copybara.transform.patch.PatchTransformation;
import com.google.copybara.util.Glob;
import com.google.copybara.util.console.Console;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * A class for reading GitHub Pull Requests
 */
public class GitHubPROrigin implements Origin<GitRevision> {

  static final int RETRY_COUNT = 3;

  public static final String GITHUB_PR_NUMBER_LABEL = "GITHUB_PR_NUMBER";
  public static final String GITHUB_BASE_BRANCH = "GITHUB_BASE_BRANCH";
  public static final String GITHUB_BASE_BRANCH_SHA1 = "GITHUB_BASE_BRANCH_SHA1";
  public static final String GITHUB_PR_USE_MERGE = "GITHUB_PR_USE_MERGE";
  public static final String GITHUB_PR_TITLE = "GITHUB_PR_TITLE";
  public static final String GITHUB_PR_URL = "GITHUB_PR_URL";
  public static final String GITHUB_PR_BODY = "GITHUB_PR_BODY";
  public static final String GITHUB_PR_USER = "GITHUB_PR_USER";
  public static final String GITHUB_PR_ASSIGNEE = "GITHUB_PR_ASSIGNEE";
  public static final String GITHUB_PR_REVIEWER_APPROVER = "GITHUB_PR_REVIEWER_APPROVER";
  public static final String GITHUB_PR_REVIEWER_OTHER = "GITHUB_PR_REVIEWER_OTHER";
  public static final String GITHUB_PR_REQUESTED_REVIEWER = "GITHUB_PR_REQUESTED_REVIEWER";
  private static final String LOCAL_PR_HEAD_REF = "refs/PR_HEAD";
  public static final String GITHUB_PR_HEAD_SHA = "GITHUB_PR_HEAD_SHA";
  private static final String LOCAL_PR_MERGE_REF = "refs/PR_MERGE";
  private static final String LOCAL_PR_BASE_BRANCH = "refs/PR_BASE_BRANCH";


  private final String url;
  private final boolean useMerge;
  private final GeneralOptions generalOptions;
  private final GitOptions gitOptions;
  private final GitOriginOptions gitOriginOptions;
  private final GitHubOptions gitHubOptions;
  private final Set<String> requiredLabelsField;
  private final Set<String> requiredStatusContextNames;
  private final Set<String> requiredCheckRuns;
  private final Set<String> retryableLabelsField;
  private final SubmoduleStrategy submoduleStrategy;
  private final Console console;
  private final boolean baselineFromBranch;
  private final Boolean firstParent;
  private final Boolean partialFetch;
  private final StateFilter requiredState;
  @Nullable private final ReviewState reviewState;
  private final ImmutableSet<AuthorAssociation> reviewApprovers;
  @Nullable private final Checker endpointChecker;
  @Nullable private final PatchTransformation patchTransformation;
  @Nullable private final String branch;
  private final boolean describeVersion;
  private final GitHubHost ghHost;
  private final GitHubPrOriginOptions gitHubPrOriginOptions;

  GitHubPROrigin(
      String url,
      boolean useMerge,
      GeneralOptions generalOptions,
      GitOptions gitOptions,
      GitOriginOptions gitOriginOptions,
      GitHubOptions gitHubOptions,
      GitHubPrOriginOptions gitHubPrOriginOptions,
      Set<String> requiredLabels,
      Set<String> requiredStatusContextNames,
      Set<String> requiredCheckRuns,
      Set<String> retryableLabels,
      SubmoduleStrategy submoduleStrategy,
      boolean baselineFromBranch,
      Boolean firstParent,
      Boolean partialClone,
      StateFilter requiredState,
      @Nullable ReviewState reviewState,
      ImmutableSet<AuthorAssociation> reviewApprovers,
      @Nullable Checker endpointChecker,
      @Nullable PatchTransformation patchTransformation,
      @Nullable String branch,
      boolean describeVersion,
      GitHubHost ghHost) {
    this.url = checkNotNull(url);
    this.useMerge = useMerge;
    this.generalOptions = checkNotNull(generalOptions);
    this.gitOptions = checkNotNull(gitOptions);
    this.gitOriginOptions = checkNotNull(gitOriginOptions);
    this.gitHubOptions = gitHubOptions;
    this.gitHubPrOriginOptions = Preconditions.checkNotNull(gitHubPrOriginOptions);
    this.requiredLabelsField = checkNotNull(requiredLabels);
    this.requiredStatusContextNames = checkNotNull(requiredStatusContextNames);
    this.requiredCheckRuns = checkNotNull(requiredCheckRuns);
    this.retryableLabelsField = checkNotNull(retryableLabels);
    this.submoduleStrategy = checkNotNull(submoduleStrategy);
    console = generalOptions.console();
    this.baselineFromBranch = baselineFromBranch;
    this.firstParent = firstParent;
    this.partialFetch = partialClone;
    this.requiredState = checkNotNull(requiredState);
    this.reviewState = reviewState;
    this.reviewApprovers = checkNotNull(reviewApprovers);
    this.endpointChecker = endpointChecker;
    this.patchTransformation = patchTransformation;
    this.branch = branch;
    this.describeVersion = describeVersion;
    this.ghHost = ghHost;
  }

  @Override
  public GitRevision resolve(String reference) throws RepoException, ValidationException {
    checkCondition(reference != null, ""
        + "A pull request reference is expected as argument in the command line."
        + " Invoke copybara as:\n"
        + "    copybara copy.bara.sky workflow_name 12345");
    console.progress("GitHub PR Origin: Resolving reference " + reference);
    String configProjectName = ghHost.getProjectNameFromUrl(url);

    // Only when requiredStatusContextNames is enabled, the reference can potentially be a sha1.
    if (!requiredStatusContextNames.isEmpty() || !requiredCheckRuns.isEmpty()
        && GitRevision.COMPLETE_SHA1_PATTERN.matcher(reference).matches()) {
      PullRequest pr = getPrToMigrate(configProjectName, reference);
      return getRevisionForPR(configProjectName, pr);
    }

    // A whole https pull request url
    Optional<GitHubPrUrl> githubPrUrl = ghHost.maybeParseGithubPrUrl(reference);
    if (githubPrUrl.isPresent()) {
      checkCondition(
          githubPrUrl.get().getProject().equals(configProjectName),
          "Project name should be '%s' but it is '%s' instead", configProjectName,
              githubPrUrl.get().getProject());
      return getRevisionForPR(configProjectName,
          getPr(configProjectName, githubPrUrl.get().getPrNumber()));
    }
    // A Pull request number
    if (CharMatcher.digit().matchesAllOf(reference)) {
      return getRevisionForPR(
          ghHost.getProjectNameFromUrl(url), getPr(configProjectName, Integer.parseInt(reference)));
    }

    // refs/pull/12345/head
    Optional<Integer> prNumber = GitHubUtil.maybeParseGithubPrFromHeadRef(reference);
    if (prNumber.isPresent()) {
      return getRevisionForPR(configProjectName, getPr(configProjectName, prNumber.get()));
    }
    String sha1Part = Splitter.on(" ").split(reference).iterator().next();
    Matcher matcher = GitRevision.COMPLETE_SHA1_PATTERN.matcher(sha1Part);
    // The only valid use case for this is to resolve previous ref.  Because we fetch the head of
    // the base branch when resolving the PR, it should exist at this point. If it doesn't then it
    // is a non-valid reference.
    // Note that this might not work if the PR is for a different branch than the imported to
    // the destination. But in this case we cannot do that much apart from --force.
    if (matcher.matches()) {
      return new GitRevision(getRepository(), getRepository().parseRef(sha1Part));
    }
    throw new CannotResolveRevisionException(
        String.format("'%s' is not a valid reference for a GitHub Pull Request. Valid formats:"
                + "'https://github.com/project/pull/1234', 'refs/pull/1234/head' or '1234'",
            reference));
  }

  @Override
  @Nullable
  public String showDiff(GitRevision revisionFrom, GitRevision revisionTo) throws RepoException {
    return getRepository().showDiff(
        checkNotNull(revisionFrom, "revisionFrom should not be null").getSha1(),
        checkNotNull(revisionTo, "revisionTo should not be null").getSha1());
  }

  private PullRequest getPrToMigrate(String project, String sha)
      throws RepoException, ValidationException {
    ImmutableList<PullRequest> pullRequests =
        gitHubOptions.newGitHubApi(project).listPullRequestsAssociatedWithACommit(project, sha);
    // Only migrate a pr with not-closed state and head being equal to sha
    ImmutableList<PullRequest> prs =
        pullRequests.stream()
            .filter(e -> !e.getState().equals("closed") && e.getHead().getSha().equals(sha))
            .collect(toImmutableList());
    if (prs.isEmpty()) {
      // Should not fail here because the pr of some SHA1 has been closed.
      throw new EmptyChangeException(
          String.format("Could not find a pr with not-closed state and head being equal to sha %s",
              sha));
    }
    // Usually, it will return one pr. But there might be an extreme case with multiple prs
    // available. We temporarily handle one pr now.
    return prs.get(0);
  }

  private PullRequest getPr(String project, long prNumber)
      throws RepoException, ValidationException {
    try (ProfilerTask ignore = generalOptions.profiler().start("github_api_get_pr")) {
      return gitHubOptions.newGitHubApi(project).getPullRequest(project, prNumber);
    }
  }

  private GitRevision getRevisionForPR(String project, PullRequest prData)
      throws RepoException, ValidationException {
    GitHubApi api = gitHubOptions.newGitHubApi(project);
    Set<String> requiredLabels = gitHubPrOriginOptions.getRequiredLabels(requiredLabelsField);
    Set<String> retryableLabels = gitHubPrOriginOptions.getRetryableLabels(retryableLabelsField);
    int prNumber = (int)prData.getNumber();
    boolean actuallyUseMerge = this.useMerge;

    if (!forceImport() && !requiredLabels.isEmpty()) {
      int retryCount = 0;
      Set<String> requiredButNotPresent;
      do {
        Issue issue;
        try (ProfilerTask ignore = generalOptions.profiler().start("github_api_get_issue")) {
          issue = api.getIssue(project, prNumber);
        }

        requiredButNotPresent = Sets.newHashSet(requiredLabels);
        requiredButNotPresent.removeAll(Collections2.transform(issue.getLabels(), Label::getName));
        // If we got all the labels we want or none of the ones we didn't get are retryable, return.
        if (requiredButNotPresent.isEmpty()
            || Collections.disjoint(requiredButNotPresent, retryableLabels)) {
          break;
        }
        Uninterruptibles.sleepUninterruptibly(2, TimeUnit.SECONDS);
        retryCount++;
      } while (retryCount < RETRY_COUNT);
      if (!requiredButNotPresent.isEmpty()) {
        throw new EmptyChangeException(String.format(
            "Cannot migrate http://github.com/%s/pull/%d because it is missing the following"
                + " labels: %s",
            project,
            prNumber,
            requiredButNotPresent));
      }
    }

    ImmutableListMultimap.Builder<String, String> labels = ImmutableListMultimap.builder();

    // check if status context names are ready if applicable
    checkRequiredStatusContextNames(api, project, prData);

    // check if check runs are ready if applicable
    checkRequiredCheckRuns(api, project, prData);
    if (!forceImport() && branch != null && !Objects.equals(prData.getBase().getRef(), branch)) {
      throw new EmptyChangeException(String.format(
          "Cannot migrate http://github.com/%s/pull/%d because its base branch is '%s', but"
              + " the workflow is configured to only migrate changes for branch '%s'",
          project,
          prNumber,
          prData.getBase().getRef(),
          branch));
    }
    if (reviewState != null) {
      ImmutableList<Review> reviews = api.getReviews(project, prNumber);
      ApproverState shouldMigrate =
          reviewState.shouldMigrate(reviews, reviewApprovers, prData.getHead().getSha());
      if (!forceImport() && !shouldMigrate.shouldMigrate()) {
        String rejected = "";
        if (!shouldMigrate.rejectedReviews().isEmpty()) {
          rejected = String.format("\nThe following reviews were ignored because they don't meet "
                  + "the association requirement of %s:\n%s",
              Joiner.on(", ").join(reviewApprovers),
              shouldMigrate.rejectedReviews().entries().stream()
                  .map(e -> String.format("User %s - Association: %s", e.getKey(), e.getValue()))
                  .collect(Collectors.joining("\n")));
        }
        throw new EmptyChangeException(String.format(
            "Cannot migrate http://github.com/%s/pull/%d because it is missing the required"
                + " approvals (origin is configured as %s).%s",
            project, prNumber, reviewState, rejected));
      }
      Set<String> approvers = new HashSet<>();
      Set<String> others = new HashSet<>();
      for (Review review : reviews) {
        if (reviewApprovers.contains(review.getAuthorAssociation())) {
          approvers.add(review.getUser().getLogin());
        } else {
          others.add(review.getUser().getLogin());
        }
      }
      labels.putAll(GITHUB_PR_REVIEWER_APPROVER, approvers);
      labels.putAll(GITHUB_PR_REVIEWER_OTHER, others);
    }

    if (!forceImport() && requiredState == StateFilter.OPEN && !prData.isOpen()) {
      throw new EmptyChangeException(String.format("Pull Request %d is not open", prNumber));
    }

    if (!forceImport() && requiredState == StateFilter.CLOSED && prData.isOpen()) {
      throw new EmptyChangeException(String.format("Pull Request %d is open", prNumber));
    }

    // Fetch also the baseline branch. It is almost free and doing a roundtrip later would hurt
    // latency.
    console.progressFmt("Fetching Pull Request %d and branch '%s'",
        prNumber, prData.getBase().getRef());
    ImmutableList.Builder<String> refSpecBuilder = ImmutableList.<String>builder()
        .add(String.format("%s:%s", asHeadRef(prNumber), LOCAL_PR_HEAD_REF))
        // Prefix the branch name with 'refs/heads/' since some implementations of
        // GitRepository need the whole reference name.
        .add(String.format("refs/heads/%s:" + LOCAL_PR_BASE_BRANCH, prData.getBase().getRef()));

    if (actuallyUseMerge) {
      if (Boolean.TRUE.equals(prData.isMergeable())) {
        refSpecBuilder.add(String.format("%s:%s", asMergeRef(prNumber), LOCAL_PR_MERGE_REF));
      } else if (forceImport()) {
        console.warnFmt(
            "PR %d is not mergeable, but continuing with PR Head instead because of %s",
            prNumber,
            GeneralOptions.FORCE);
        actuallyUseMerge = false;
      } else if (prData.isMergeable() == null) {
        throw new CannotResolveRevisionException(
            String.format(
                "Cannot find a merge reference for Pull Request %d."
                    + " GitHub might still be generating it.",
                prNumber));
      } else {
        throw new CannotResolveRevisionException(
            String.format(
                "Cannot find a merge reference for Pull Request %d."
                    + " It might have a conflict with head.",
                prNumber));
      }
    }

    ImmutableList<String> refspec = refSpecBuilder.build();
    try (ProfilerTask ignore = generalOptions.profiler().start("fetch")) {
      getRepository()
          .fetch(
              ghHost.projectAsUrl(project),
              /*prune=*/ false,
              /*force=*/ true,
              refspec,
              partialFetch);
    } catch (CannotResolveRevisionException e) {
      if (actuallyUseMerge) {
        throw new CannotResolveRevisionException(
            String.format("Cannot find a merge reference for Pull Request %d, even though GitHub"
                + " reported that this merge reference should exist.", prNumber), e);
      } else {
        throw new CannotResolveRevisionException(
            String.format("Cannot find Pull Request %d.", prNumber), e);
      }
    }

    String refForMigration = actuallyUseMerge ? LOCAL_PR_MERGE_REF : LOCAL_PR_HEAD_REF;
    GitRevision gitRevision = getRepository().resolveReference(refForMigration);

    String headPrSha1 = getRepository().resolveReference(LOCAL_PR_HEAD_REF).getSha1();
    String integrateLabel = new GitHubPRIntegrateLabel(getRepository(), generalOptions,
        project, prNumber,
        prData.getHead().getLabel(),
        // The integrate SHA has to be HEAD of the PR not the merge ref, even if use_merge = True
        headPrSha1)
        .toString();

    labels.putAll(
        GITHUB_PR_REQUESTED_REVIEWER,
        prData.getRequestedReviewers().stream().map(User::getLogin).collect(toImmutableList()));
    labels.put(GITHUB_PR_NUMBER_LABEL, Integer.toString(prNumber));
    labels.put(GitModule.DEFAULT_INTEGRATE_LABEL, integrateLabel);
    labels.put(GITHUB_BASE_BRANCH, prData.getBase().getRef());
    labels.put(GITHUB_PR_HEAD_SHA, headPrSha1);
    labels.put(GITHUB_PR_USE_MERGE, Boolean.toString(actuallyUseMerge));

    String mergeBase = getRepository().mergeBase(refForMigration, LOCAL_PR_BASE_BRANCH);
    labels.put(GITHUB_BASE_BRANCH_SHA1, mergeBase);

    labels.put(GITHUB_PR_TITLE, prData.getTitle());
    labels.put(GITHUB_PR_BODY, prData.getBody());
    labels.put(GITHUB_PR_URL, prData.getHtmlUrl());
    labels.put(GITHUB_PR_USER, prData.getUser().getLogin());
    labels.putAll(GITHUB_PR_ASSIGNEE, prData.getAssignees().stream()
        .map(User::getLogin)
        .collect(Collectors.toList()));

    GitRevision result = new GitRevision(
        getRepository(),
        gitRevision.getSha1(),
        // TODO(malcon): Decide the format to use here:
        /*reviewReference=*/null,
        actuallyUseMerge ? asMergeRef(prNumber) : asHeadRef(prNumber),
        labels.build(),
        url);

    return describeVersion ? getRepository().addDescribeVersion(result) : result;
  }

  private void checkRequiredCheckRuns(GitHubApi api, String project, PullRequest prData)
      throws ValidationException, RepoException {
    if (forceImport() || requiredCheckRuns.isEmpty()) {
      return;
    }
    try (ProfilerTask ignore = generalOptions.profiler()
        .start("github_api_get_combined_status")) {
      CheckRuns checkRuns = api.getCheckRuns(project, prData.getHead().getSha());
      Set<String> requiredButNotPresent = Sets.newHashSet(requiredCheckRuns);
      List<CheckRun> passedCheckRuns = checkRuns.getCheckRuns().stream()
          .filter(e -> e.getConclusion().equals("success")).collect(Collectors.toList());
      requiredButNotPresent.removeAll(Collections2.transform(passedCheckRuns,
          CheckRun::getName));
      if (!requiredButNotPresent.isEmpty()) {
        throw new EmptyChangeException(String.format(
            "Cannot migrate http://github.com/%s/pull/%d because the following check runs "
                + "have not been passed: %s",
            project,
            prData.getNumber(),
            requiredButNotPresent));
      }
    }
  }

  private void checkRequiredStatusContextNames(GitHubApi api, String project, PullRequest prData)
      throws ValidationException, RepoException {
    if (forceImport() || requiredStatusContextNames.isEmpty()) {
      return;
    }
    try (ProfilerTask ignore = generalOptions.profiler()
        .start("github_api_get_combined_status")) {
      CombinedStatus combinedStatus = api.getCombinedStatus(project, prData.getHead().getSha());
      Set<String> requiredButNotPresent = Sets.newHashSet(requiredStatusContextNames);
      List<Status> successStatuses = combinedStatus.getStatuses().stream()
          .filter(e -> e.getState() == State.SUCCESS).collect(Collectors.toList());
      requiredButNotPresent.removeAll(Collections2.transform(successStatuses,
          Status::getContext));
      if (!requiredButNotPresent.isEmpty()) {
        throw new EmptyChangeException(String.format(
            "Cannot migrate http://github.com/%s/pull/%d because the following ci labels "
                + "have not been passed: %s",
            project,
            prData.getNumber(),
            requiredButNotPresent));
      }
    }
  }

  @VisibleForTesting
  public GitRepository getRepository() throws RepoException {
    return gitOptions.cachedBareRepoForUrl(url);
  }

  @Override
  public Reader<GitRevision> newReader(Glob originFiles, Authoring authoring)
      throws ValidationException {
    return new ReaderImpl(
        url,
        originFiles,
        authoring,
        gitOptions,
        gitOriginOptions,
        generalOptions,
        /*includeBranchCommitLogs=*/ false,
        submoduleStrategy,
        firstParent,
        partialFetch,
        patchTransformation,
        describeVersion,
        /*configPath=*/ null,
        /*workflowName=*/ null) {

      /** Disable rebase since this is controlled by useMerge field. */
      @Override
      protected void maybeRebase(GitRepository repo, GitRevision ref, Path workdir)
          throws RepoException, CannotResolveRevisionException {}

      @Override
      public Optional<Baseline<GitRevision>> findBaseline(GitRevision startRevision, String label)
          throws RepoException, ValidationException {
        if (!baselineFromBranch) {
          return super.findBaseline(startRevision, label);
        }
        return findBaselinesWithoutLabel(startRevision, /*limit=*/ 1).stream()
            .map(e -> new Baseline<>(e.getSha1(), e))
            .findFirst();
      }

      @Override
      public ImmutableList<GitRevision> findBaselinesWithoutLabel(
          GitRevision startRevision, int limit) throws RepoException, ValidationException {
        String baseline =
            Iterables.getLast(startRevision.associatedLabels().get(GITHUB_BASE_BRANCH_SHA1), null);
        checkNotNull(
            baseline, "%s label should be present in %s", GITHUB_BASE_BRANCH_SHA1, startRevision);

        GitRevision baselineRev = getRepository().resolveReference(baseline);
        // Don't skip the first change as it is already the baseline
        BaselinesWithoutLabelVisitor<GitRevision> visitor =
            new BaselinesWithoutLabelVisitor<>(originFiles, limit, /*skipFirst=*/ false);
        visitChanges(baselineRev, visitor);
        return visitor.getResult();
      }

      @Override
      public Endpoint getFeedbackEndPoint(Console console) throws ValidationException {
        gitHubOptions.validateEndpointChecker(endpointChecker);
        return new GitHubEndPoint(
            gitHubOptions.newGitHubApiSupplier(url, endpointChecker, ghHost), url, console, ghHost);
      }

      /**
       * Deal with the case of useMerge. We have a new commit (the merge) and first-parent from that
       * commit doesn't work for this case.
       */
      @Override
      public ChangesResponse<GitRevision> changes(@Nullable GitRevision fromRef, GitRevision toRef)
          throws RepoException, ValidationException {
        checkCondition(
            toRef.associatedLabels().containsKey(GITHUB_PR_USE_MERGE),
            "Cannot determine whether 'use_merge' was set.");
        if (toRef.associatedLabel(GITHUB_PR_USE_MERGE).contains("false")) {
          return super.changes(fromRef, toRef);
        }
        GitLogEntry merge =
            Iterables.getOnlyElement(getRepository().log(toRef.getSha1()).withLimit(1).run());
        // Fast-forward merge
        if (merge.getParents().size() == 1) {
          return super.changes(fromRef, toRef);
        }
        // HEAD of the Pull Request
        GitRevision gitRevision = merge.getParents().get(1);
        ChangesResponse<GitRevision> prChanges = super.changes(fromRef, gitRevision);
        // Merge might have an effect, but we are not interested on it if the PR doesn't touch
        // origin_files
        if (prChanges.isEmpty()) {
          return prChanges;
        }
        try {
          return ChangesResponse.forChanges(
              ImmutableList.<Change<GitRevision>>builder()
                  .addAll(prChanges.getChanges())
                  .add(change(merge.getCommit()))
                  .build());
        } catch (EmptyChangeException e) {
          throw new RepoException("Error getting the merge commit information: " + merge, e);
        }
      }
    };
  }

  @Override
  public String getLabelName() {
    return GitRepository.GIT_ORIGIN_REV_ID;
  }

  @Override
  public String getType() {
    return "git.github_pr_origin";
  }

  @VisibleForTesting
  public ReviewState getReviewState() {
    return reviewState;
  }

  @VisibleForTesting
  public Set<String> getRequiredLabels() {
    return gitHubPrOriginOptions.getRequiredLabels(requiredLabelsField);
  }

  @Override
  public ImmutableSetMultimap<String, String> describe(Glob originFiles) {
    ImmutableSetMultimap.Builder<String, String> builder =
        new ImmutableSetMultimap.Builder<String, String>()
            .put("type", getType())
            .put("url", url);
    if (branch != null) {
      builder.put("branch", branch);
    }
    if (!originFiles.roots().isEmpty() && !originFiles.roots().contains("")) {
      builder.putAll("root", originFiles.roots());
    }
    if (reviewState != null) {
      builder.put("review_state", reviewState.name());
      builder.putAll(
          "review_approvers", reviewApprovers.stream().map(Enum::name).collect(toImmutableList()));
    }
    if (!requiredStatusContextNames.isEmpty()) {
        builder.putAll(GitHubUtil.REQUIRED_STATUS_CONTEXT_NAMES, requiredStatusContextNames);
    }
    if (!requiredCheckRuns.isEmpty()) {
      builder.putAll(GitHubUtil.REQUIRED_CHECK_RUNS, requiredCheckRuns);
    }
    return builder.build();
  }

  private boolean forceImport() {
    return generalOptions.isForced() || gitHubPrOriginOptions.forceImport;
  }

  /**
   * Only migrate PR in one of the following states:
   */
  enum StateFilter {
    OPEN,
    CLOSED,
    ALL
  }

  @VisibleForTesting
  public enum ReviewState {
    /**
     * Requires that the current head commit has at least one valid approval
     */
    HEAD_COMMIT_APPROVED {
      @Override
      boolean shouldMigrate(ImmutableList<Review> reviews, String sha) {
        return reviews.stream()
            .filter(e -> e.getCommitId().equals(sha))
            .anyMatch(Review::isApproved);
      }
    },
    /**
     * Any valid approval, even for old commits is good.
     */
    ANY_COMMIT_APPROVED {
      @Override
      boolean shouldMigrate(ImmutableList<Review> reviews, String sha) {
        return reviews.stream().anyMatch(Review::isApproved);
      }
    },
    /**
     * There are reviewers in the change that have commented, asked for changes or approved
     */
    HAS_REVIEWERS {
      @Override
      boolean shouldMigrate(ImmutableList<Review> reviews, String sha) {
        return !reviews.isEmpty();
      }
    },
    /**
     * Import the change regardless of the the review state. It will populate the appropriate
     * labels if found
     */
    ANY {
      @Override
      boolean shouldMigrate(ImmutableList<Review> reviews, String sha) {
        return true;
      }
    };

    ApproverState shouldMigrate(ImmutableList<Review> reviews,
        ImmutableSet<AuthorAssociation> approvers, String sha) {
      return ApproverState.create(shouldMigrate(
          reviews.stream()
              // Only take into account reviews by valid approverTypes
              .filter(e -> approvers.contains(e.getAuthorAssociation()))
              .collect(toImmutableList()),
          sha),
          reviews.stream()
              .filter(e -> !approvers.contains(e.getAuthorAssociation()))
              .collect(toImmutableList()));
    }

    abstract boolean shouldMigrate(ImmutableList<Review> reviews, String sha);
  }

  @AutoValue
  abstract static class ApproverState {
    public abstract boolean shouldMigrate();
    public abstract ImmutableListMultimap<String, String> rejectedReviews();

    public static ApproverState create(
        boolean shouldMigrate, ImmutableList<Review> rejectedReviews) {
      return new AutoValue_GitHubPROrigin_ApproverState(
          shouldMigrate,
          rejectedReviews.stream().collect(ImmutableListMultimap.toImmutableListMultimap(
              r -> r.getUser().getLogin(), r -> r.getAuthorAssociation().toString())));
    }
  }
}
