/*
 * Copyright 2015-2017 Canoo Engineering AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.canoo.dolphin.impl;

import com.canoo.dolphin.BeanManager;
import com.canoo.dolphin.event.BeanAddedListener;
import com.canoo.platform.core.functional.Subscription;
import com.canoo.dolphin.internal.BeanBuilder;
import com.canoo.dolphin.internal.BeanRepository;
import com.canoo.impl.platform.core.Assert;

import java.util.List;

public class BeanManagerImpl implements BeanManager {

    protected final BeanRepository beanRepository;
    private final BeanBuilder beanBuilder;

    public BeanManagerImpl(final BeanRepository beanRepository, final BeanBuilder beanBuilder) {
        this.beanRepository = Assert.requireNonNull(beanRepository, "beanRepository");
        this.beanBuilder = Assert.requireNonNull(beanBuilder, "beanBuilder");
    }

    @Override
    public <T> T create(final Class<T> beanClass) {
        DolphinUtils.assertIsDolphinBean(beanClass);
        return beanBuilder.create(beanClass);
    }

    @Override
    public <T> List<T> findAll(final Class<T> beanClass) {
        DolphinUtils.assertIsDolphinBean(beanClass);
        return beanRepository.findAll(beanClass);
    }

    @Override
    public <T> Subscription onAdded(final Class<T> beanClass, final BeanAddedListener<? super T> listener) {
        DolphinUtils.assertIsDolphinBean(beanClass);
        return beanRepository.addOnAddedListener(beanClass, listener);
    }
}
