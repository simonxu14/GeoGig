/* Copyright (c) 2013 OpenPlans. All rights reserved.
 * This code is licensed under the BSD New License, available at the root
 * application directory.
 */

package org.geogit.api.plumbing;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import org.geogit.api.AbstractGeoGitOp;
import org.geogit.api.ObjectId;
import org.geogit.api.RevCommit;
import org.geogit.storage.GraphDatabase;
import org.geogit.storage.GraphDatabase.Direction;
import org.geogit.storage.GraphDatabase.GraphEdge;
import org.geogit.storage.GraphDatabase.GraphNode;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.inject.Inject;

/**
 * Finds the common {@link RevCommit commit} ancestor of two commits.
 */
public class FindCommonAncestor extends AbstractGeoGitOp<Optional<ObjectId>> {

    private ObjectId left;

    private ObjectId right;

    private GraphDatabase graphDb;

    /**
     * Construct a new {@code FindCommonAncestor} using the specified {@link GraphDatabase}.
     * 
     * @param repository the repository
     */
    @Inject
    public FindCommonAncestor(GraphDatabase graphDb) {
        this.graphDb = graphDb;
    }

    /**
     * @param left the left {@link ObjectId}
     */
    public FindCommonAncestor setLeftId(ObjectId left) {
        this.left = left;
        return this;
    }

    /**
     * @param right the right {@link ObjectId}
     */
    public FindCommonAncestor setRightId(ObjectId right) {
        this.right = right;
        return this;
    }

    /**
     * @param left the left {@link RevCommit}
     */
    public FindCommonAncestor setLeft(RevCommit left) {
        this.left = left.getId();
        return this;
    }

    /**
     * @param right the right {@link RevCommit}
     */
    public FindCommonAncestor setRight(RevCommit right) {
        this.right = right.getId();
        return this;
    }

    /**
     * Finds the common {@link RevCommit commit} ancestor of two commits.
     * 
     * @return an {@link Optional} of the ancestor commit, or {@link Optional#absent()} if no common
     *         ancestor was found
     */
    @Override
    public Optional<ObjectId> call() {
        Preconditions.checkState(left != null, "Left commit has not been set.");
        Preconditions.checkState(right != null, "Right commit has not been set.");

        if (left.equals(right)) {
            // They are the same commit
            return Optional.of(left);
        }

        getProgressListener().started();

        Optional<ObjectId> ancestor = findLowestCommonAncestor(left, right);

        getProgressListener().complete();

        return ancestor;
    }

    /**
     * Finds the lowest common ancestor of two commits.
     * 
     * @param leftId the commit id of the left commit
     * @param rightId the commit id of the right commit
     * @return An {@link Optional} of the lowest common ancestor of the two commits, or
     *         {@link Optional#absent()} if a common ancestor could not be found.
     */
    public Optional<ObjectId> findLowestCommonAncestor(ObjectId leftId, ObjectId rightId) {
        Set<GraphNode> leftSet = new HashSet<GraphNode>();
        Set<GraphNode> rightSet = new HashSet<GraphNode>();

        Queue<GraphNode> leftQueue = new LinkedList<GraphNode>();
        Queue<GraphNode> rightQueue = new LinkedList<GraphNode>();

        GraphNode leftNode = graphDb.getNode(leftId);
        leftQueue.add(leftNode);

        GraphNode rightNode = graphDb.getNode(rightId);
        rightQueue.add(rightNode);

        List<GraphNode> potentialCommonAncestors = new LinkedList<GraphNode>();
        while (!leftQueue.isEmpty() || !rightQueue.isEmpty()) {
            if (!leftQueue.isEmpty()) {
                GraphNode commit = leftQueue.poll();
                if (processCommit(commit, leftQueue, leftSet, rightQueue, rightSet)) {
                    potentialCommonAncestors.add(commit);
                }
            }
            if (!rightQueue.isEmpty()) {
                GraphNode commit = rightQueue.poll();
                if (processCommit(commit, rightQueue, rightSet, leftQueue, leftSet)) {
                    potentialCommonAncestors.add(commit);
                }
            }
        }
        verifyAncestors(potentialCommonAncestors, leftSet, rightSet);

        Optional<ObjectId> ancestor = Optional.absent();
        if (potentialCommonAncestors.size() > 0) {
            ancestor = Optional.of(potentialCommonAncestors.get(0).getIdentifier());
        }
        return ancestor;
    }

    private boolean processCommit(GraphNode commit, Queue<GraphNode> myQueue, Set<GraphNode> mySet,
            Queue<GraphNode> theirQueue, Set<GraphNode> theirSet) {
        if (!mySet.contains(commit)) {
            mySet.add(commit);
            if (theirSet.contains(commit)) {
                stopAncestryPath(commit, theirQueue, theirSet);
                return true;
            }
            for (GraphEdge parentEdge : commit.getEdges(Direction.OUT)) {
                GraphNode parent = parentEdge.getToNode();
                myQueue.add(parent);
            }
        }
        return false;

    }

    private void stopAncestryPath(GraphNode commit, Queue<GraphNode> theirQueue,
            Set<GraphNode> theirSet) {
        Queue<GraphNode> ancestorQueue = new LinkedList<GraphNode>();
        ancestorQueue.add(commit);
        List<GraphNode> processed = new LinkedList<GraphNode>();
        while (!ancestorQueue.isEmpty()) {
            GraphNode ancestor = ancestorQueue.poll();
            for (GraphEdge parent : ancestor.getEdges(Direction.BOTH)) {
                GraphNode parentNode = parent.getToNode();
                if (!parentNode.getIdentifier().equals(ancestor.getIdentifier())) {
                    if (theirSet.contains(parentNode)) {
                        ancestorQueue.add(parentNode);
                        processed.add(parentNode);
                    }
                } else if (theirQueue.contains(parentNode)) {
                    theirQueue.remove(parentNode);
                }
            }
        }
    }

    private void verifyAncestors(List<GraphNode> potentialCommonAncestors, Set<GraphNode> leftSet,
            Set<GraphNode> rightSet) {
        Queue<GraphNode> ancestorQueue = new LinkedList<GraphNode>();
        List<GraphNode> falseAncestors = new LinkedList<GraphNode>();
        List<GraphNode> processed = new LinkedList<GraphNode>();

        for (GraphNode v : potentialCommonAncestors) {
            if (falseAncestors.contains(v)) {
                continue;
            }
            ancestorQueue.add(v);
            while (!ancestorQueue.isEmpty()) {
                GraphNode ancestor = ancestorQueue.poll();
                for (GraphEdge parent : ancestor.getEdges(Direction.OUT)) {
                    GraphNode parentNode = parent.getToNode();
                    if (parentNode.getIdentifier() != ancestor.getIdentifier()) {
                        if (leftSet.contains(parentNode) || rightSet.contains(parentNode)) {
                            if (!processed.contains(parentNode)) {
                                ancestorQueue.add(parentNode);
                                processed.add(parentNode);
                            }
                            if (potentialCommonAncestors.contains(parentNode)) {
                                falseAncestors.add(parentNode);
                            }
                        }
                    }
                }
            }
        }
        potentialCommonAncestors.removeAll(falseAncestors);
    }
}
