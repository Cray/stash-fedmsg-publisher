package com.cray.stash;

import com.atlassian.stash.commit.*;
import com.atlassian.stash.content.Change;
import com.atlassian.stash.exception.AuthorisationException;
import com.atlassian.stash.repository.*;
import com.atlassian.stash.server.ApplicationPropertiesService;
import com.atlassian.stash.user.Permission;
import com.atlassian.stash.user.SecurityService;
import com.atlassian.stash.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Created by swalter on 6/3/2016.
 */
public class SEPCommitsImpl implements SEPCommits {
    private RefService repoData;
    private CommitService commitService;
    private SecurityService security;
    private RepositoryService repoService;
    private int pageLimit;
    private ApplicationPropertiesService appService;
    private static final Logger LOGGER = LoggerFactory.getLogger(SEPCommitsImpl.class);
    private String topicPrefix;

    public SEPCommitsImpl(RefService repoData, CommitService commitService, SecurityService security, RepositoryService repoService, ApplicationPropertiesService appService) {
        this.repoData = repoData;
        this.commitService = commitService;
        this.security = security;
        this.repoService = repoService;
        this.appService = appService;

        try {
            pageLimit = Integer.parseInt(appService.getPluginProperty("plugin.fedmsg.pageLimit"));
            topicPrefix = appService.getPluginProperty("plugin.fedmsg.events.topic.prefix");
        } catch (Exception e) {
            LOGGER.error("Failed to retrieve page limit property, error message was: " + e.getMessage());
        }

        if (pageLimit == 0) {
            pageLimit = 250;
            LOGGER.info("The page limit was not set so it's set to 250 by default.");
        }

        if (topicPrefix == null) {
            topicPrefix = "com.cray.dev.stash.";
            LOGGER.info("The topic prefix was empty so it's set to the dev environment by default.");
        }
    }

    @Override
    public ArrayList<Message> findCommitInfo(RefChange ref, Repository repo) {
        ArrayList<Message> toSend = new ArrayList<Message>();
        Page<Commit> commits = getChangeset(repo, ref);
        for (Commit commit : commits.getValues()) {
            String topic = topicPrefix + repo.getProject().getKey() + "." + repo.getName() + ".commit";
            Message message = new Message(getInfo(commit), topic);
            toSend.add(message);
        }
        return toSend;
    }

    /*
    * This method takes an individual commit object, and extracts the information from it that we want to send to
    * Fedmsg. This method is for use with the pushEvent method, so strictly refChanges.
    */
    private HashMap<String, Object> getInfo(Commit commit) {
        TimeZone tz = TimeZone.getTimeZone("UTC");
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        df.setTimeZone(tz);

        HashMap<String, Object> content = new HashMap<String, Object>();
        HashMap<String, String> author = new HashMap<String, String>(2);
        try {
            author.put("name", commit.getAuthor().getName());
            author.put("emailAddress", commit.getAuthor().getEmailAddress());
            content.put("author", author);
            content.put("comments", commit.getMessage());
            content.put("project_key", commit.getRepository().getProject().getKey());
            content.put("urls", getCloneUrls(commit.getRepository()));
            content.put("repository", commit.getRepository().getName());
            content.put("project", commit.getRepository().getProject().getName());
            content.put("revision", commit.getId());
            content.put("when_timestamp", df.format(commit.getAuthorTimestamp()));
            content.put("branch", commit.getDisplayId());
            content.put("files", getFiles(commit));
        } catch (NullPointerException e) {
            LOGGER.error("NullPointerException occurred while extracting information from a commit object. Commit Message: " + commit.getMessage()
                    + " author: " + commit.getAuthor().getName() + " commit id: " + commit.getDisplayId() + "\nMessage: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Exception occurred while extracting information from a commit object. Commit Message: " + commit.getMessage()
                    + " author: " + commit.getAuthor().getName() + " commit id: " + commit.getDisplayId() + "\nMessage: " + e.getMessage());
        }
        return content;
    }

    /*
    * This method gets commit(s) in between two refs.
    */
    private Page<Commit> getChangeset(Repository repo, RefChange ref) {
        CommitsBetweenRequest.Builder commitsRequest = new CommitsBetweenRequest.Builder(repo);
        commitsRequest.exclude(getExcludes(repo, ref));
        commitsRequest.include(ref.getToHash());
        return commitService.getCommitsBetween(commitsRequest.build(), PageUtils.newRequest(0, pageLimit));
    }

    /*
    * This method takes in a repository and returns a set of the latest ref ids for all of
    * its branches except for the one we're currently analyzing. That one could have new
    * commits on it and we don't want to miss those.
    */
    private Set<String> getExcludes(Repository repo, RefChange ref) {
        final RepositoryBranchesRequest branchesRequest = new RepositoryBranchesRequest.Builder(repo).build();
        Page<Branch> branches = repoData.getBranches(branchesRequest, PageUtils.newRequest(0, 100));
        Set<String> refIds = new HashSet<String>(branches.getSize());
        try {
            for (Branch branch : branches.getValues()) {
                if(branch.getId().startsWith("refs/heads/") && !branch.getId().equals(ref.getRefId())){
                    refIds.add(branch.getDisplayId());
                }
            }
        } catch (Exception e) {
            LOGGER.error("An error occurred while finding all the branches in a repo: " + e.getMessage());
        }

        if(!ref.getFromHash().contains("0000000000000000000000000000000000000000")){
            refIds.add(ref.getFromHash());
        }
        return refIds;
    }

    private ArrayList getFiles(Commit commit) {
        // This is the request to grab the change data, which is where we find the file path info
        final ChangesetsRequest.Builder changesRequestBuilder = new ChangesetsRequest.Builder(commit.getRepository());
        ChangesetsRequest changesRequest = changesRequestBuilder.commitIds(commit.getId()).build();
        final Page<Changeset> page = commitService.getChangesets(changesRequest, PageUtils.newRequest(0, pageLimit));

        ArrayList<String> filesChanged = new ArrayList<String>();
        for (Changeset change : page.getValues()) {
            for (Change files : change.getChanges().getValues()) {
                filesChanged.add(files.getPath().toString());
            }
        }

        return filesChanged;
    }

    /*
    * Returns a set of the various links (http, ssh) that a particular scm (git, svn) can clone from.
    */
    private HashMap<String, String> getCloneUrls(Repository repo) {
        final RepositoryCloneLinksRequest linksRequest = new RepositoryCloneLinksRequest.Builder()
                .repository(repo)
                .build();
        HashMap<String, String> links = new HashMap<String, String>(2);
        try {
            Set<NamedLink> setLinks = security.withPermission(Permission.ADMIN, "Requesting Clone URLs").call(new UncheckedOperation<Set<NamedLink>>() {
                @Override
                public Set<NamedLink> perform() {
                    return repoService.getCloneLinks(linksRequest);
                }
            });
            for (NamedLink link : setLinks) {
                if (link.getHref().contains("https://")) {
                    links.put(link.getName() + "_url", "https://" + link.getHref().substring(link.getHref().indexOf("@") + 1));
                } else if (link.getHref().contains("http://")) {
                    links.put(link.getName() + "_url", "http://" + link.getHref().substring(link.getHref().indexOf("@") + 1));
                } else {
                    links.put(link.getName() + "_url", link.getHref());
                }
            }
        } catch (AuthorisationException e) {
            LOGGER.error("AuthorisationException occurred while finding clone urls: " + e.getMessage());
        } catch (IllegalStateException e) {
            LOGGER.error("IllegalStateException occurred while finding clone urls: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.error("Exception occurred while finding clone urls: " + e.getMessage());
        }
        return links;
    }
}
