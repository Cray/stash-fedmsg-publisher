package com.cray.stash;

import com.atlassian.event.api.EventListener;
import com.atlassian.stash.commit.CommitService;
import com.atlassian.stash.content.*;
import com.atlassian.stash.event.RepositoryRefsChangedEvent;
import com.atlassian.stash.event.pull.*;
import com.atlassian.stash.notification.pull.PullRequestReviewerAddedNotification;
import com.atlassian.stash.pull.PullRequest;
import com.atlassian.stash.pull.PullRequestAction;
import com.atlassian.stash.pull.PullRequestParticipant;
import com.atlassian.stash.repository.*;
import com.atlassian.stash.user.StashUser;
import com.atlassian.stash.util.NamedLink;
import com.atlassian.stash.util.Page;
import com.atlassian.stash.util.PageUtils;
import com.atlassian.stash.server.ApplicationPropertiesService;

import org.fedoraproject.fedmsg.FedmsgConnection;
import org.fedoraproject.fedmsg.FedmsgMessage;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

public class FedmsgEventListener {
    private CommitService commitService;
    private String endpoint;
    private FedmsgConnection connect;
    private RepositoryMetadataService repoData;
    private RepositoryService repoService;
    private String topicPrefix;
    private ApplicationPropertiesService appService;

    public FedmsgEventListener(CommitService commitService, RepositoryMetadataService repoData, RepositoryService repoService, ApplicationPropertiesService appService) {
        this.commitService = commitService;
        this.repoData = repoData;
        this.repoService = repoService;
        this.appService = appService;
        //This is the address of the relay_inbound
        this.endpoint = appService.getPluginProperty("plugin.fedmsg.events.relay.endpoint");
        // The connection to that endpoint
        topicPrefix = appService.getPluginProperty("plugin.fedmsg.events.topic.prefix");
        this.connect = new FedmsgConnection(endpoint, 2000).connect();
    }

    /*
     * These are helper methods to determine if a branch is newly created or deleted.
     */
    private boolean isCreated(RefChange ref) {
        if (ref.getFromHash().contains("0000000000000000000000000000000000000000")) {
            return true;
        } else {
            return false;
        }
    }
    private boolean isDeleted(RefChange ref) {
        if (ref.getToHash().contains("0000000000000000000000000000000000000000")) {
            return true;
        } else {
            return false;
        }
    }

    /*
     * This method takes in a repository and returns a set of the latest ref ids for all of
     * its branches except for the one we're currently analyzing. That one could have new
     * commits on it and we don't want to miss those.
     */
    private HashSet<String> getLatestRefs(Repository repo, RefChange ref) {
        final RepositoryBranchesRequest branchesRequest = new RepositoryBranchesRequest.Builder()
                .repository(repo)
                .build();

        Page<Branch> branches = repoData.getBranches(branchesRequest, PageUtils.newRequest(0, 100));
        HashSet<String> refIds = new HashSet<String>(branches.getSize());
        for (Branch branch : branches.getValues()) {
            if(!branch.getId().contentEquals(ref.getRefId())) {
                refIds.add(branch.getLatestChangeset());
            }
        }
        return refIds;
    }

    /*
     * This is a helper method for all events, it simply sends the message to Fedmsg with
     * a specified topic and prepends an topic prefix, environment, and modname.
     */
    private void sendMessage(Message message) {
        FedmsgMessage msg = new FedmsgMessage(
                message.getMessage(),
                (topicPrefix + message.getTopic()).toLowerCase(),
                (new java.util.Date()).getTime() / 1000,
                1);
        try {
            connect.send(msg);
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    /*
     * This method signals the creation/deletion of a branch event and sends a Fedmsg message regarding that.
     */
    private void sendBranchChangeMessage(String state, RefChange ref, Repository repo) {
        String topic = repo.getProject().getKey() + "." + repo.getName() + ".branch." + state;
        HashMap<String, Object> content = new HashMap<String, Object>();
        content.put("repository", repo.getName());
        content.put("project_key", repo.getProject().getKey());
        content.put("branch", ref.getRefId());
        content.put("urls", getCloneUrls(repo));
        content.put("state", state);
        repo.getProject().getKey();
        Message message = new Message(content, topic);
        sendMessage(message);
    }

    /*
     * This method processes the creation/deletion of a tag and sends a Fedmsg message regarding that.
     */
    private void sendTagMessage(String state, RefChange ref, Repository repo, HashSet<String> latestRefIds)
    {
        String topic = repo.getProject().getKey() + "." + repo.getName() + ".tag." + state;
        HashMap<String, Object> content = new HashMap<String, Object>();
        TimeZone tz = TimeZone.getTimeZone("UTC");
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'");
        df.setTimeZone(tz);
        ArrayList<Changeset> tagCommits = preProcessChangeset(repo, ref, latestRefIds);

        content.put("repository", repo.getName());
        content.put("project_key", repo.getProject().getKey());
        content.put("tag", ref.getRefId());
        content.put("urls", getCloneUrls(repo));
        content.put("revision", ref.getToHash());
        content.put("state", state);
        if(tagCommits.size() > 0) {
            content.put("author", tagCommits.get(0).getAuthor());
            content.put("tag message", tagCommits.get(0).getMessage());
            content.put("when_timestamp", df.format(tagCommits.get(0).getAuthorTimestamp()));
        }
        Message message = new Message(content, topic);
        sendMessage(message);
    }

    /*
     * This method gets commit(s) in between two refs.
     */
    private Page<Changeset> getChangeset(Repository repository, RefChange refChange) {
        final ChangesetsBetweenRequest changesetRequest = new ChangesetsBetweenRequest.Builder(repository)
                .exclude(refChange.getFromHash())
                .include(refChange.getToHash())
                .build();
        return commitService.getChangesetsBetween(changesetRequest, PageUtils.newRequest(0, 9999));
    }

    /*
     * This method figures out which commits are already upstream (as best as it can) and then copies
     * the new commits to an array list that it returns for usage in processChanges(...). This method
     * exists for when someone creates a branch with new commits on it and pushes that new branch
     * upstream.
     */
    private ArrayList<Changeset> preProcessChangeset(Repository repo, RefChange ref, HashSet<String> latestRefIds) {
        // List of messages to send across the fedmsg bus that have not already been processed
        ArrayList<Changeset> newCommits = new ArrayList<Changeset>();

        // Page of all commits, likely containing ones we've already processed
        Page<Changeset> commits = getChangeset(repo, ref);
        // Process commits newest to oldest
        for (Changeset commit : commits.getValues()) {
            if (latestRefIds.contains(commit.getId())) {
                break;
            } else {
                newCommits.add(commit);
            }
        }
        return newCommits;
    }

    /*
     * Returns a set of the various links (http, ssh) that a particular scm (git, svn) can clone from.
     */
    private HashMap<String, String> getCloneUrls(Repository repo) {
        final RepositoryCloneLinksRequest linksRequest = new RepositoryCloneLinksRequest.Builder()
                .repository(repo)
                .build();
        Set<NamedLink> setLinks = repoService.getCloneLinks(linksRequest);
        HashMap<String, String> links = new HashMap<String, String>(2);
        for(NamedLink link: setLinks) {
            if(link.getHref().contains("http://")) {
                links.put(link.getName() + "_url", "http://" + link.getHref().substring(link.getHref().indexOf("@") + 1));
            }
            else {
                links.put(link.getName() + "_url", link.getHref());
            }
        }
        return links;
    }

    /*
     * This method takes an individual commit object, and extracts the information from it that we want to send to
     * Fedmsg. This method is for use with the pushEvent method, so strictly refChanges.
     */
    private HashMap<String, Object> refChangeExtracter(Changeset commit, ArrayList<String> files, String branch)
    {
        TimeZone tz = TimeZone.getTimeZone("UTC");
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'");
        df.setTimeZone(tz);

        HashMap<String, Object> content = new HashMap<String, Object>();
        HashMap<String, String> author = new HashMap<String, String>(2);
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
        content.put("branch", branch);
        content.put("files", files);

        return content;
    }

    /*
     * This method takes an ArrayList of commits (in between two ref Changes) and constructs a list of
     * message to send. All commits in the ArrayList should be new commits.
     */
    private ArrayList<Message> processChanges(ArrayList<Changeset> commits, RefChange ref)
    {
        //list of messages to send across the fedmsg bus
        ArrayList<HashMap<String, Object>> toSend = new ArrayList<HashMap<String, Object>>();

        // Parsing backwards through the new commits
        for (int i = 0; i < commits.size(); i++) {
            // This is the request to grab the change data, which is where we find the file path info
            final DetailedChangesetsRequest changesRequest = new DetailedChangesetsRequest.Builder(commits.get(i).getRepository())
                    .changesetId(commits.get(i).getId())
                    .maxChangesPerCommit(9999)
                    .build();

            final Page<DetailedChangeset> page = commitService.getDetailedChangesets(changesRequest, PageUtils.newRequest(0, 9999));

            ArrayList<String> filesChanged = new ArrayList<String>();
            for (DetailedChangeset change : page.getValues())
            {
                for (Change files : change.getChanges().getValues())
                {
                    filesChanged.add(files.getPath().toString());
                }
            }
            toSend.add(refChangeExtracter(commits.get(i), filesChanged, ref.getRefId().substring(11)));
        }

        ListIterator<HashMap<String, Object>> li = toSend.listIterator(toSend.size());
        ArrayList<Message> messageList = new ArrayList<Message>(toSend.size());
        while(li.hasPrevious())
        {
            HashMap<String, Object> sending = li.previous();
            String topic = sending.get("project_key").toString() + "." + sending.get("repository").toString() + ".commit";
            Message message = new Message(sending, topic);
            sendMessage(message);
            messageList.add(message);
        }

        return messageList;
    }

    /*
     * This method handles all of the different types of events that might occur when an refChangeEvent happens.
     * This could be merges, pushes, new branches, or tag creations.
     */
    @EventListener
    public void refChangeListener(RepositoryRefsChangedEvent event) {
        Collection<RefChange> refChanges = event.getRefChanges();
        Repository repository = event.getRepository();

        for (RefChange refChange : refChanges) {
            HashSet<String> latestRefIds = getLatestRefs(repository, refChange);

            // Are we dealing with a new tag/branch or just normal commits?
            if(isCreated(refChange) || isDeleted(refChange)) {
                // Are we dealing with a tag/branch refChange?
                if(!refChange.getRefId().contains("refs/tags")) {
                    // Branch creation
                    if (isCreated(refChange)) {
                        sendBranchChangeMessage("created", refChange, repository);
                        processChanges(preProcessChangeset(repository, refChange, latestRefIds), refChange);
                    }
                    // Branch deletion
                    else if(isDeleted(refChange)) {
                        sendBranchChangeMessage("deleted", refChange, repository);
                    }
                }
                // Tag refChange
                else if(refChange.getRefId().contains("refs/tags")){
                    // Tag creation
                    if(isCreated(refChange)) {
                        sendTagMessage("created", refChange, repository, latestRefIds);
                    }
                    // Tag deletion
                    else if(isDeleted(refChange)) {
                        sendTagMessage("deleted", refChange, repository, latestRefIds);
                    }
                }
            }
            // This is just new commits (or a merge commit), no tags or branches.
            else {
                processChanges(preProcessChangeset(repository, refChange, latestRefIds), refChange);
            }
        }
    }

    /*
     * This method extracts all the relevant information from a pull request event and packages it into
     * a HashMap for usage with the Fedmsg commands. It is similar to the refChangeExtracter.
     */
    public HashMap<String, Object> prExtracter (PullRequestEvent event)
    {
        PullRequest pr = event.getPullRequest();
        // Time format bits.
        TimeZone tz = TimeZone.getTimeZone("UTC");
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'");
        df.setTimeZone(tz);

        // Extract information from the PR event
        HashMap<String, String> author = new HashMap<String, String>(2);
        HashMap<String, Object> message = new HashMap<String, Object>();

        author.put("name", pr.getAuthor().getUser().getDisplayName());
        author.put("emailAddress", pr.getAuthor().getUser().getEmailAddress());

        Repository originRepo = pr.getFromRef().getRepository();
        String originProject = pr.getFromRef().getRepository().getProject().getName();
        Repository destRepo = pr.getToRef().getRepository();
        String destProject = pr.getToRef().getRepository().getProject().getName();
        String originProjectKey = pr.getFromRef().getRepository().getProject().getKey();
        String destProjectKey = pr.getToRef().getRepository().getProject().getKey();
        Long id = pr.getId();
        String desc = pr.getDescription();
        Date date = pr.getCreatedDate();
        String state = pr.getState().name();
        Set<PullRequestParticipant> reviewers = pr.getReviewers();

        // Creating the source branch sub dictionary
        HashMap<String, Object> source = new HashMap<String, Object>();
        source.put("urls", getCloneUrls(originRepo));
        source.put("repository", originRepo.getName());
        source.put("project", originProject);
        source.put("branch", pr.getFromRef().getDisplayId());
        source.put("project_key", originProjectKey);

        // Creating the destination branch dictionary
        HashMap<String, Object> destination = new HashMap<String, Object>();
        destination.put("urls", getCloneUrls(destRepo));
        destination.put("repository", destRepo.getName());
        destination.put("project", destProject);
        destination.put("branch", pr.getFromRef().getDisplayId());
        destination.put("project_key", destProjectKey);

        // Construct the body of the fedmsg message
        message.put("author", author);
        message.put("when_timestamp", df.format(date));
        message.put("source", source);
        message.put("destination", destination);
        message.put("id", id);
        message.put("description", desc);
        message.put("state", state);
        ArrayList<String> reviewerList = new ArrayList<String>(reviewers.size());
        if (!reviewers.isEmpty()) {
            /* "PullRequestParticipants" are not serializable by JSON (in order to send messages
             * via Fedmsg, the contents of the message must be able to be encoded by JSON)
             * so we need to create a new list of just strings for each reviewer.
             */
            for (PullRequestParticipant person : reviewers) {
                reviewerList.add(person.getUser().getDisplayName());
            }
            message.put("reviewers", reviewerList);

        }

        return message;
    }

    /*
     * This method is for constructing the basic pull request event message. It extracts all of the
     * necessary information from the event, and creates a Message object to then send via fedmsg.
     */
    public Message getMessage(PullRequestEvent event, String type)
    {
        HashMap<String, Object> content = prExtracter(event);
        String originProjectKey = ((HashMap<String, Object>)content.get("source")).get("project_key").toString();
        String originRepo = ((HashMap<String, Object>)content.get("source")).get("repository").toString();
        String topic = originProjectKey + "." + originRepo + type;
        Message message = new Message(content, topic);
        return message;
    }

    /*
     * This getMessage method handles when the approval status changes. Depending on the event
     * the topic needs to change and also the content of the message.
     */
    public Message getMessage(PullRequestApprovalEvent event)
    {
        PullRequestAction action = event.getAction();

        HashMap<String, Object> content = prExtracter(event);
        String originProjectKey = ((HashMap<String, Object>)content.get("source")).get("project_key").toString();
        String originRepo = ((HashMap<String, Object>)content.get("source")).get("repository").toString();
        String topic = "";
        if(action == PullRequestAction.APPROVED){
            topic = originProjectKey + "." + originRepo + ".pullrequest.approved";
            content.put("approver", event.getParticipant().getUser().getDisplayName());
        }
        else if(action == PullRequestAction.UNAPPROVED) {
            topic = originProjectKey + "." + originRepo + ".pullrequest.unapproved";
            content.put("disprover", event.getParticipant().getUser().getDisplayName());
        }
        Message message = new Message(content, topic);
        return message;
    }

    /*
     * This getMessage method handles specific reviewersmodified event. It needs to
     * extract a list of reviewers added and removed and append that to the message.
     */
    public Message getMessage(PullRequestRolesUpdatedEvent event, String type)
    {
        Set<StashUser> added = event.getAddedReviewers();
        Set<StashUser> removed = event.getRemovedReviewers();

        HashMap<String, Object> content = prExtracter(event);
        String originProjectKey = ((HashMap<String, Object>)content.get("source")).get("project_key").toString();
        String originRepo = ((HashMap<String, Object>)content.get("source")).get("repository").toString();

        if(!added.isEmpty()) {
            ArrayList<String> addedList = new ArrayList<String>(added.size());
            for(StashUser user: added) {
                addedList.add(user.getDisplayName());
            }
            content.put("reviewers_added", addedList);
        }
        if(!removed.isEmpty()) {
            ArrayList<String> removedList = new ArrayList<String>(removed.size());
            for(StashUser user: removed) {
                removedList.add(user.getDisplayName());
            }
            content.put("reviewers_removed", removedList);
        }

        String topic = originProjectKey + "." + originRepo + type;
        Message message = new Message(content, topic);
        return message;
    }

    /*
     * The following event handlers handles all of the pull requests events we care about, currently:
     * merged, declined, approved, opened, and unapproved
     */

    /*
     * This event fires when someone hits the "merge" button on an open pull request. It sends a Fedmsg
     * message to the relevant topic about this event.
     */
    @EventListener
    public void merged(PullRequestMergedEvent event) {
        Message message = getMessage(event,  ".pullrequest.merged");
        sendMessage(message);
    }

    /*
     * This event fires when someone opens a pull request and sends a Fedmsg message to a relevant topic
     * about the event.
     */
    @EventListener
    public void opened(PullRequestOpenedEvent event) {
        Message message = getMessage(event,  ".pullrequest.opened");
        sendMessage(message);
    }

    /*
     * This event fires if someone declines the pull request. It also sends a Fedmsg message to the relevant
     * topic. For some reason, this event doesn't store who did the approving, so that is not part of the
     * message.
     */
    @EventListener
    public void declined(PullRequestDeclinedEvent event) {
        Message message = getMessage(event,  ".pullrequest.declined");
        sendMessage(message);
    }

    /*
     * This event fires when the approval status of a pull request changes. It sends a Fedmsg message to
     * the relevant topic and adds in who is responsible for the change in status.
     */
    @EventListener
    public void approvalStatusChange(PullRequestApprovalEvent event) {
        Message message = getMessage(event);
        sendMessage(message);
    }

    /*
     * This event fires when reviewers for a pull request are modified and sends a Fedmsg message to
     * the relevant topic with a list of the newly added or recently deleted reviewer(s).
     */
    @EventListener
    public void reviewersModified(PullRequestRolesUpdatedEvent event) {
        Message message = getMessage(event,  ".pullrequest.reviewersmodified");
        sendMessage(message);
    }
}
