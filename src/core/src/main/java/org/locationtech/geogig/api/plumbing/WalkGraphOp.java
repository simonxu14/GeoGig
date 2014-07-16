/* Copyright (c) 2013 OpenPlans. All rights reserved.
 * This code is licensed under the BSD New License, available at the root
 * application directory.
 */
package org.locationtech.geogig.api.plumbing;

import java.util.Iterator;

import org.locationtech.geogig.api.AbstractGeoGigOp;
import org.locationtech.geogig.api.ObjectId;
import org.locationtech.geogig.api.RevObject;
import org.locationtech.geogig.repository.PostOrderIterator;
import org.locationtech.geogig.storage.Deduplicator;

import com.google.common.base.Optional;
import com.google.common.collect.Iterators;

public class WalkGraphOp extends AbstractGeoGigOp<Iterator<RevObject>> {

    private String reference;
    
    private Deduplicator deduplicator;

    public WalkGraphOp setReference(final String reference) {
        this.reference = reference;
        return this;
    }
    
    public WalkGraphOp setDeduplicator(final Deduplicator deduplicator) {
    	this.deduplicator = deduplicator;
    	return this;
    }

    @Override
    protected Iterator<RevObject> _call() {
        Optional<ObjectId> ref = command(RevParse.class).setRefSpec(reference).call();
        if (!ref.isPresent())
            return Iterators.emptyIterator();
        if (deduplicator == null) throw new IllegalStateException("The caller must provide a deduplicator!");
        return PostOrderIterator.all(ref.get(), objectDatabase(), deduplicator);
    }
}
