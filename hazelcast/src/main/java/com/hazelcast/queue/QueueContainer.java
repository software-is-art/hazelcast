/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.queue;

import com.hazelcast.config.QueueConfig;
import com.hazelcast.config.QueueStoreConfig;
import com.hazelcast.monitor.impl.LocalQueueStatsImpl;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.nio.serialization.DataSerializable;
import com.hazelcast.nio.serialization.SerializationService;
import com.hazelcast.spi.exception.RetryableHazelcastException;
import com.hazelcast.util.Clock;

import java.io.IOException;
import java.util.*;

/**
 * User: ali
 * Date: 11/22/12
 * Time: 11:00 AM
 */
public class QueueContainer implements DataSerializable {

    private final LinkedList<QueueItem> itemQueue = new LinkedList<QueueItem>();

    private final LinkedHashMap<String, List<QueueItem>> txOfferMap = new LinkedHashMap<String, List<QueueItem>>();

    private final LinkedHashMap<String, List<QueueItem>> txPollMap = new LinkedHashMap<String, List<QueueItem>>();

    private final HashMap<Long, Data> dataMap = new HashMap<Long, Data>();

    private final String name;

    private final QueueWaitNotifyKey pollWaitNotifyKey;
    private final QueueWaitNotifyKey offerWaitNotifyKey;

    private int partitionId;

    private QueueConfig config;

    private long idGen = 0;

    private QueueStoreWrapper store;

    private volatile long minAge;

    private volatile long maxAge;

    private volatile long totalAge;

    private volatile long totalAgedCount;

    public QueueContainer(String name) {
        this.name = name;
        pollWaitNotifyKey = new QueueWaitNotifyKey(name, "poll");
        offerWaitNotifyKey = new QueueWaitNotifyKey(name, "offer");
    }

    public QueueContainer(String name, int partitionId, QueueConfig config, SerializationService serializationService, boolean fromBackup) throws Exception {
        this.name = name;
        pollWaitNotifyKey = new QueueWaitNotifyKey(name, "poll");
        offerWaitNotifyKey = new QueueWaitNotifyKey(name, "offer");
        this.partitionId = partitionId;
        setConfig(config, serializationService);
        if (!fromBackup && store.isEnabled()) {
            Set<Long> keys = store.loadAllKeys();
            if (keys != null) {
                for (Long key : keys) {
                    QueueItem item = new QueueItem(this, key);
                    itemQueue.offer(item);
                    idGen++;
                }
            }
        }
    }

    public void commit(String txId) {
        List<QueueItem> list = txOfferMap.remove(txId);
        if (list != null) {
            for (QueueItem item : list) {
                item.setItemId(idGen++);
                itemQueue.offer(item);
            }
        }
        list = txPollMap.remove(txId);
        if (list != null) {
            long current = Clock.currentTimeMillis();
            for (QueueItem item : list) {
                age(item, current);
            }
        }
    }

    public void rollback(String txId) {
        List<QueueItem> list = txPollMap.remove(txId);
        if (list != null) {
            ListIterator<QueueItem> iter = list.listIterator(list.size());
            while (iter.hasPrevious()) {
                QueueItem item = iter.previous();
                if (item.getItemId() != -1) {
                    itemQueue.offerFirst(item);
                }
            }
        }
        txOfferMap.remove(txId);
    }

    public boolean txOffer(String txId, Data data) {
        List<QueueItem> list = getTxList(txId, txOfferMap);
        return list.add(new QueueItem(this, -1, data));
    }

    public Data txPoll(String txId) {
        QueueItem item = itemQueue.poll();
        if (item == null) {
            List<QueueItem> list = getTxList(txId, txOfferMap);
            if (list.size() > 0) {
                item = list.remove(0);
            }
        }
        if (item != null) {
            List<QueueItem> list = getTxList(txId, txPollMap);
            list.add(item);
            return item.getData();
        }
        return null;
    }

    public Data txPeek(String txId) {
        QueueItem item = itemQueue.peek();
        if (item == null) {
            List<QueueItem> list = getTxList(txId, txOfferMap);
            if (list.size() > 0) {
                item = list.get(0);
            }
        }
        return item == null ? null : item.getData();
    }

    private List<QueueItem> getTxList(String txId, Map<String, List<QueueItem>> map) {
        List<QueueItem> list = map.get(txId);
        if (list == null) {
            list = new ArrayList<QueueItem>(3);
            map.put(txId, list);
        }
        return list;
    }


    public boolean offer(Data data) {
        QueueItem item = new QueueItem(this, idGen++);
        if (store.isEnabled()) {
            try {
                store.store(item.getItemId(), data);
            } catch (Exception e) {
                idGen--;
                throw new RetryableHazelcastException(e);
            }
        }
        if (!store.isEnabled() || store.getMemoryLimit() > itemQueue.size()) {
            item.setData(data);
        }
        return itemQueue.offer(item);
    }

    public QueueItem offerBackup(Data data) {
        QueueItem item = new QueueItem(this, idGen++);
        if (!store.isEnabled() || store.getMemoryLimit() > itemQueue.size()) {
            item.setData(data);
        }
        itemQueue.offer(item);
        return item;
    }

    public int size() {
        return itemQueue.size(); //TODO check max size
    }

    public List<Data> clear() {
        Set<Long> keySet = new HashSet<Long>(itemQueue.size());
        List<Data> dataList = new ArrayList<Data>(itemQueue.size());
        for (QueueItem item : itemQueue) {
            keySet.add(item.getItemId());
            dataList.add(item.getData());
        }
        if (store.isEnabled()) {
            try {
                store.deleteAll(keySet);
            } catch (Exception e) {
                throw new RetryableHazelcastException(e);
            }
        }
        long current = Clock.currentTimeMillis();
        for (QueueItem item : itemQueue) {
            age(item, current); // For stats
        }
        itemQueue.clear();
        dataMap.clear();
        return dataList;
    }

    public void clearBackup() {
        long current = Clock.currentTimeMillis();
        for (QueueItem item : itemQueue) {
            age(item, current); // For stats
        }
        itemQueue.clear();
        dataMap.clear();
    }

    public Data poll() {
        QueueItem item = peek();
        if (item == null) {
            return null;
        }
        if (store.isEnabled()) {
            try {
                store.delete(item.getItemId());
            } catch (Exception e) {
                throw new RetryableHazelcastException(e);
            }
        }
        itemQueue.poll();
        age(item, Clock.currentTimeMillis());
        return item.getData();
    }

    public void pollBackup() {
        QueueItem item = itemQueue.poll();
        age(item, Clock.currentTimeMillis());//For Stats
    }

    /**
     * iterates all items, checks equality with data
     * This method does not trigger store load.
     */
    public long remove(Data data) {
        Iterator<QueueItem> iter = itemQueue.iterator();
        while (iter.hasNext()) {
            QueueItem item = iter.next();
            if (data.equals(item.getData())) {
                if (store.isEnabled()) {
                    try {
                        store.delete(item.getItemId());
                    } catch (Exception e) {
                        throw new RetryableHazelcastException(e);
                    }
                }
                iter.remove();
                age(item, Clock.currentTimeMillis()); //For Stats
                return item.getItemId();
            }
        }
        return -1;
    }

    public void removeBackup(long itemId) {
        Iterator<QueueItem> iter = itemQueue.iterator();
        while (iter.hasNext()) {
            QueueItem item = iter.next();
            if (item.getItemId() == itemId) {
                iter.remove();
                age(item, Clock.currentTimeMillis()); //For Stats
                return;
            }
        }
    }

    public QueueItem peek() {
        QueueItem item = itemQueue.peek();
        if (item == null) {
            return null;
        }
        if (store.isEnabled() && item.getData() == null) {
            try {
                load(item);
            } catch (Exception e) {
                throw new RetryableHazelcastException(e);
            }
        }
        return item;
    }

    /**
     * This method does not trigger store load.
     */
    public boolean contains(Collection<Data> dataSet) {
        Set<QueueItem> set = new HashSet<QueueItem>(dataSet.size());
        for (Data data : dataSet) {
            set.add(new QueueItem(this, -1, data));
        }
        return itemQueue.containsAll(set);
    }

    /**
     * This method triggers store load.
     */
    public List<Data> getAsDataList() {
        List<Data> dataList = new ArrayList<Data>(itemQueue.size());
        for (QueueItem item : itemQueue) {
            if (store.isEnabled() && item.getData() == null) {
                try {
                    load(item);
                } catch (Exception e) {
                    throw new RetryableHazelcastException(e);
                }
            }
            dataList.add(item.getData());
        }
        return dataList;
    }

    public Collection<Data> drain(int maxSize) {
        if (maxSize < 0 || maxSize > itemQueue.size()) {
            maxSize = itemQueue.size();
        }
        LinkedHashMap<Long, Data> map = new LinkedHashMap<Long, Data>(maxSize);
        Iterator<QueueItem> iter = itemQueue.iterator();
        for (int i = 0; i < maxSize; i++) {
            QueueItem item = iter.next();
            if (store.isEnabled() && item.getData() == null) {
                try {
                    load(item);
                } catch (Exception e) {
                    throw new RetryableHazelcastException(e);
                }
            }
            map.put(item.getItemId(), item.getData());
        }
        if (store.isEnabled()) {
            try {
                store.deleteAll(map.keySet());
            } catch (Exception e) {
                throw new RetryableHazelcastException(e);
            }
        }
        long current = Clock.currentTimeMillis();
        for (int i = 0; i < maxSize; i++) {
            QueueItem item = itemQueue.poll();
            age(item, current); //For Stats
        }
        return map.values();
    }

    public void drainFromBackup(int maxSize) {
        if (maxSize < 0) {
            clearBackup();
            return;
        }
        if (maxSize > itemQueue.size()) {
            maxSize = itemQueue.size();
        }
        for (int i = 0; i < maxSize; i++) {
            pollBackup();
        }

    }

    public void addAll(Collection<Data> dataList) {
        Map<Long, Data> dataMap = new HashMap<Long, Data>(dataList.size());
        for (Data data : dataList) {
            QueueItem item = offerBackup(data);
            dataMap.put(item.getItemId(), data);
        }
        if (store.isEnabled()) {
            try {
                store.storeAll(dataMap);
            } catch (Exception e) {
                for (int i = 0; i < dataList.size(); i++) {
                    itemQueue.poll();
                    idGen--;
                }
                throw new RetryableHazelcastException(e);
            }
        }
    }

    public void addAllBackup(Collection<Data> dataList) {
        for (Data data : dataList) {
            offerBackup(data);
        }
    }

    /**
     * This method triggers store load
     */
    public Map<Long, Data> compareAndRemove(Collection<Data> dataList, boolean retain) {
        LinkedHashMap<Long, Data> map = new LinkedHashMap<Long, Data>();
        for (QueueItem item : itemQueue) {
            if (item.getData() == null && store.isEnabled()) {
                try {
                    load(item);
                } catch (Exception e) {
                    throw new RetryableHazelcastException(e);
                }
            }
            boolean contains = dataList.contains(item.getData());
            if ((retain && !contains) || (!retain && contains)) {
                map.put(item.getItemId(), item.getData());
            }
        }
        if (map.size() > 0) {
            if (store.isEnabled()) {
                try {
                    store.deleteAll(map.keySet());
                } catch (Exception e) {
                    throw new RetryableHazelcastException(e);
                }
            }
            Iterator<QueueItem> iter = itemQueue.iterator();
            long current = Clock.currentTimeMillis();
            while (iter.hasNext()) {
                QueueItem item = iter.next();
                if (map.containsKey(item.getItemId())) {
                    iter.remove();
                    age(item, current);
                }
            }
        }
        return map;
    }

    public void compareAndRemoveBackup(Set<Long> keySet) {
        Iterator<QueueItem> iter = itemQueue.iterator();
        long current = Clock.currentTimeMillis();
        while (iter.hasNext()) {
            QueueItem item = iter.next();
            if (keySet.contains(item.getItemId())) {
                iter.remove();
                age(item, current);
            }
        }
    }

    public int getPartitionId() {
        return partitionId;
    }

    public QueueConfig getConfig() {
        return config;
    }

    public void setConfig(QueueConfig config, SerializationService serializationService) {
        store = new QueueStoreWrapper(serializationService);
        this.config = new QueueConfig(config);
        QueueStoreConfig storeConfig = config.getQueueStoreConfig();
        store.setConfig(storeConfig);
    }

    private void load(QueueItem item) throws Exception {
        int bulkLoad = store.getBulkLoad();
        bulkLoad = Math.min(itemQueue.size(), bulkLoad);
        if (bulkLoad == 1) {
            item.setData(store.load(item.getItemId()));
        } else if (bulkLoad > 1) {
            ListIterator<QueueItem> iter = itemQueue.listIterator();
            HashSet<Long> keySet = new HashSet<Long>(bulkLoad);
            for (int i = 0; i < bulkLoad; i++) {
                keySet.add(iter.next().getItemId());
            }
            Map<Long, Data> values = store.loadAll(keySet);
            dataMap.putAll(values);
            item.setData(getDataFromMap(item.getItemId()));
        }
    }

    public boolean hasEnoughCapacity() {
        return checkBound(1);
    }

    public boolean checkBound(int delta) {
        return (itemQueue.size() + delta) <= config.getMaxSize();
    }

    public Data getDataFromMap(long itemId) {
        return dataMap.remove(itemId);
    }

    public void writeData(ObjectDataOutput out) throws IOException {
        out.writeInt(partitionId);
        out.writeInt(itemQueue.size());
        for (QueueItem item : itemQueue) {
            item.writeData(out);
        }
        out.writeInt(txOfferMap.size());
        for (Map.Entry<String, List<QueueItem>> entry : txOfferMap.entrySet()) {
            String txId = entry.getKey();
            out.writeUTF(txId);
            List<QueueItem> list = entry.getValue();
            out.writeInt(list.size());
            for (QueueItem item : list) {
                item.writeData(out);
            }
        }
        out.writeInt(txPollMap.size());
        for (Map.Entry<String, List<QueueItem>> entry : txPollMap.entrySet()) {
            String txId = entry.getKey();
            out.writeUTF(txId);
            List<QueueItem> list = entry.getValue();
            out.writeInt(list.size());
            for (QueueItem item : list) {
                item.writeData(out);
            }
        }

    }

    public void readData(ObjectDataInput in) throws IOException {
        partitionId = in.readInt();
        int size = in.readInt();
        for (int j = 0; j < size; j++) {
            QueueItem item = new QueueItem(this);
            item.readData(in);
            itemQueue.offer(item);
            idGen++;
        }
        int offerMapSize = in.readInt();
        for (int i = 0; i < offerMapSize; i++) {
            String txId = in.readUTF();
            int listSize = in.readInt();
            List<QueueItem> list = new ArrayList<QueueItem>(listSize);
            for (int j = 0; j < listSize; j++) {
                QueueItem item = new QueueItem(this);
                item.readData(in);
                list.add(item);
            }
            txOfferMap.put(txId, list);
        }
        int pollMapSize = in.readInt();
        for (int i = 0; i < pollMapSize; i++) {
            String txId = in.readUTF();
            int listSize = in.readInt();
            List<QueueItem> list = new ArrayList<QueueItem>(listSize);
            for (int j = 0; j < listSize; j++) {
                QueueItem item = new QueueItem(this);
                item.readData(in);
                list.add(item);
            }
            txPollMap.put(txId, list);
        }
    }

    private void age(QueueItem item, long currentTime) {
        long elapsed = currentTime - item.getCreationTime();
        if (elapsed <= 0) {
            return;//elapsed time can not be a negative value, a system clock problem maybe. ignored
        }
        totalAgedCount++;
        totalAge += elapsed;
        if (minAge == 0) {
            minAge = elapsed;
            maxAge = elapsed;
        } else {
            minAge = Math.min(minAge, elapsed);
            maxAge = Math.max(maxAge, elapsed);
        }
    }

    public void setStats(LocalQueueStatsImpl stats) {
        stats.setMinAge(minAge);
        minAge = 0;
        stats.setMaxAge(maxAge);
        maxAge = 0;
        long totalAgeVal = totalAge;
        totalAge = 0;
        long totalAgedCountVal = totalAgedCount;
        totalAgedCount = 0;
        stats.setAveAge(totalAgedCountVal == 0 ? 0 : totalAgeVal / totalAgedCountVal);
    }

    public QueueWaitNotifyKey getPollWaitNotifyKey() {
        return pollWaitNotifyKey;
    }

    public QueueWaitNotifyKey getOfferWaitNotifyKey() {
        return offerWaitNotifyKey;
    }
}