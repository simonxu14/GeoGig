/* Copyright (c) 2013 OpenPlans. All rights reserved.
 * This code is licensed under the BSD New License, available at the root
 * application directory.
 */
package org.locationtech.geogig.di;

import org.locationtech.geogig.api.AbstractGeoGigOp;
import org.locationtech.geogig.api.plumbing.merge.ConflictsCheckOp;

import com.google.common.base.Preconditions;

/**
 * Intercepts all {@link AbstractGeoGigOp commands} to avoid incompatible running commands while
 * merge conflicts exist
 * 
 */
class ConflictInterceptor implements Decorator {

    @Override
    public boolean canDecorate(Object subject) {
        if (!(subject instanceof AbstractGeoGigOp)) {
            return false;
        }
        // TODO: this is not a very clean way of doing this...
        Class<?> clazz = subject.getClass();
        final boolean canRunDuringConflict = clazz.isAnnotationPresent(CanRunDuringConflict.class);
        return !(clazz.getPackage().getName().contains("plumbing") || canRunDuringConflict);
    }

    @Override
    public AbstractGeoGigOp<?> decorate(Object subject) {
        Preconditions.checkNotNull(subject);
        AbstractGeoGigOp<?> operation = (AbstractGeoGigOp<?>) subject;

        Boolean conflicts = operation.command(ConflictsCheckOp.class).call();
        if (conflicts.booleanValue()) {
            throw new IllegalStateException("Cannot run operation while merge conflicts exist.");
        }
        return (AbstractGeoGigOp<?>) subject;
    }

}
