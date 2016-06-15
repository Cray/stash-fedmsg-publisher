package com.cray.stash;

import com.atlassian.stash.repository.RefChange;
import com.atlassian.stash.repository.Repository;
import java.util.List;

/**
 * Created by swalter on 6/3/2016.
 */
public interface SEPCommits {
    List<Message> findCommitInfo (RefChange ref, Repository repo);
}
