/* Copyright (c) 2013 OpenPlans. All rights reserved.
 * This code is licensed under the BSD New License, available at the root
 * application directory.
 */
package org.locationtech.geogig.storage.datastream;

import org.locationtech.geogig.storage.ObjectSerializingFactory;
import org.locationtech.geogig.storage.RevFeatureSerializationTest;

public class DataStreamFeatureV2SerializationTest extends RevFeatureSerializationTest {
    @Override
    protected ObjectSerializingFactory getObjectSerializingFactory() {
        return new DataStreamSerializationFactoryV2();
    }
}