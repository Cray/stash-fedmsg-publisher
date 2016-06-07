package com.cray.stash;

import com.atlassian.stash.repository.RefChange;
import com.atlassian.stash.repository.Repository;

import java.util.ArrayList;

/**
 * Created by swalter on 6/3/2016.
 */
public interface SEPCommits {
    ArrayList<Message> findCommitInfo (RefChange ref, Repository repo);
}
