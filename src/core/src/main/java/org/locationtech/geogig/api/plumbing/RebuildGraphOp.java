/* Copyright (c) 2013 OpenPlans. All rights reserved.
 * This code is licensed under the BSD New License, available at the root
 * application directory.
 */
package org.locationtech.geogig.api.plumbing;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.locationtech.geogig.api.AbstractGeoGigOp;
import org.locationtech.geogig.api.ObjectId;
import org.locationtech.geogig.api.Ref;
import org.locationtech.geogig.api.RevCommit;
import org.locationtech.geogig.api.porcelain.BranchListOp;
import org.locationtech.geogig.api.porcelain.LogOp;
import org.locationtech.geogig.repository.Repository;
import org.locationtech.geogig.storage.GraphDatabase;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

/**
 * Rebuilds the {@link GraphDatabase} and returns a list of {@link ObjectId}s that were found to be
 * missing or incomplete.
 */
public class RebuildGraphOp extends AbstractGeoGigOp<ImmutableList<ObjectId>> {

    /**
     * Executes the {@code RebuildGraphOp} operation.
     * 
     * @return a list of {@link ObjectId}s that were found to be missing or incomplete
     */
    @Override
    protected ImmutableList<ObjectId> _call() {
        Repository repository = repository();
        Preconditions.checkState(!repository.isSparse(),
                "Cannot rebuild the graph of a sparse repository.");

        List<ObjectId> updated = new LinkedList<ObjectId>();
        ImmutableList<Ref> branches = command(BranchListOp.class).setLocal(true).setRemotes(true)
                .call();

        GraphDatabase graphDb = repository.graphDatabase();

        for (Ref ref : branches) {
            Iterator<RevCommit> commits = command(LogOp.class).setUntil(ref.getObjectId()).call();
            while (commits.hasNext()) {
                RevCommit next = commits.next();
                if (graphDb.put(next.getId(), next.getParentIds())) {
                    updated.add(next.getId());
                }
            }
        }

        return ImmutableList.copyOf(updated);
    }
}
