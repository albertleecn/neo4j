/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.index.schema;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.neo4j.cursor.RawCursor;
import org.neo4j.index.internal.gbptree.Hit;
import org.neo4j.index.internal.gbptree.Writer;
import org.neo4j.io.ByteUnit;
import org.neo4j.io.IOUtils;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.index.IndexDirectoryStructure;
import org.neo4j.kernel.api.index.IndexEntryUpdate;
import org.neo4j.kernel.api.index.IndexProvider;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.impl.api.index.PhaseTracker;
import org.neo4j.kernel.impl.index.schema.config.IndexSpecificSpaceFillingCurveSettingsCache;
import org.neo4j.kernel.impl.index.schema.config.SpaceFillingCurveSettingsWriter;
import org.neo4j.storageengine.api.schema.StoreIndexDescriptor;
import org.neo4j.util.FeatureToggles;
import org.neo4j.util.Preconditions;
import org.neo4j.values.storable.Value;

import static org.neo4j.kernel.impl.index.schema.NativeIndexUpdater.initializeKeyFromUpdate;
import static org.neo4j.kernel.impl.index.schema.NativeIndexes.deleteIndex;

public abstract class BlockBasedIndexPopulator<KEY extends NativeIndexKey<KEY>,VALUE extends NativeIndexValue> extends NativeIndexPopulator<KEY,VALUE>
{
    private static final String BLOCK_SIZE = FeatureToggles.getString( BlockBasedIndexPopulator.class, "blockSize", "1M" );
    private static final int MERGE_FACTOR = FeatureToggles.getInteger( BlockBasedIndexPopulator.class, "mergeFactor", 8 );

    private static final ByteBufferFactory BYTE_BUFFER_FACTORY = ByteBuffer::allocate;

    private final IndexDirectoryStructure directoryStructure;
    private final boolean archiveFailedIndex;
    private final int blockSize;
    // written to in a synchronized method when creating new thread-local instances, read from when population completes
    private final List<BlockStorage<KEY,VALUE>> allScanUpdates = new CopyOnWriteArrayList<>();
    private final ThreadLocal<BlockStorage<KEY,VALUE>> scanUpdates;
    private IndexUpdateStorage<KEY,VALUE> externalUpdates;
    // written in a synchronized method when creating new thread-local instances, read when processing external updates
    private volatile boolean merged;

    BlockBasedIndexPopulator( PageCache pageCache, FileSystemAbstraction fs, File file, IndexLayout<KEY,VALUE> layout, IndexProvider.Monitor monitor,
            StoreIndexDescriptor descriptor, IndexSpecificSpaceFillingCurveSettingsCache spatialSettings,
            IndexDirectoryStructure directoryStructure, boolean archiveFailedIndex )
    {
        super( pageCache, fs, file, layout, monitor, descriptor, new SpaceFillingCurveSettingsWriter( spatialSettings ) );
        this.directoryStructure = directoryStructure;
        this.archiveFailedIndex = archiveFailedIndex;
        this.blockSize = parseBlockSize();
        this.scanUpdates = ThreadLocal.withInitial( this::newThreadLocalBlockStorage );
    }

    private synchronized BlockStorage<KEY,VALUE> newThreadLocalBlockStorage()
    {
        Preconditions.checkState( !merged, "Already merged" );
        try
        {
            int id = allScanUpdates.size();
            BlockStorage<KEY,VALUE> blockStorage =
                    new BlockStorage<>( layout, BYTE_BUFFER_FACTORY, fileSystem, new File( storeFile.getParentFile(), storeFile.getName() + ".scan-" + id ),
                            BlockStorage.Monitor.NO_MONITOR, blockSize );
            allScanUpdates.add( blockStorage );
            return blockStorage;
        }
        catch ( IOException e )
        {
            throw new UncheckedIOException( e );
        }
    }

    private static int parseBlockSize()
    {
        long blockSize = ByteUnit.parse( BLOCK_SIZE );
        Preconditions.checkArgument( blockSize >= 20 && blockSize < Integer.MAX_VALUE, "Block size need to fit in int. Was " + blockSize );
        return (int) blockSize;
    }

    @Override
    public void create()
    {
        try
        {
            deleteIndex( fileSystem, directoryStructure, descriptor.getId(), archiveFailedIndex );
        }
        catch ( IOException e )
        {
            throw new UncheckedIOException( e );
        }
        super.create();
        try
        {
            externalUpdates = new IndexUpdateStorage<>( layout, fileSystem, new File( storeFile.getParent(), storeFile.getName() + ".ext" ),
                    BYTE_BUFFER_FACTORY, blockSize );
        }
        catch ( IOException e )
        {
            throw new UncheckedIOException( e );
        }
    }

    @Override
    public void add( Collection<? extends IndexEntryUpdate<?>> updates )
    {
        if ( !updates.isEmpty() )
        {
            BlockStorage<KEY,VALUE> blockStorage = scanUpdates.get();
            for ( IndexEntryUpdate<?> update : updates )
            {
                storeUpdate( update, blockStorage );
            }
        }
    }

    private void storeUpdate( long entityId, Value[] values, BlockStorage<KEY,VALUE> blockStorage )
    {
        try
        {
            KEY key = layout.newKey();
            VALUE value = layout.newValue();
            initializeKeyFromUpdate( key, entityId, values );
            value.from( values );
            blockStorage.add( key, value );
        }
        catch ( IOException e )
        {
            throw new UncheckedIOException( e );
        }
    }

    private void storeUpdate( IndexEntryUpdate<?> update, BlockStorage<KEY,VALUE> blockStorage )
    {
        storeUpdate( update.getEntityId(), update.values(), blockStorage );
    }

    @Override
    public void scanCompleted( PhaseTracker phaseTracker ) throws IndexEntryConflictException
    {
        try
        {
            phaseTracker.enterPhase( PhaseTracker.Phase.MERGE );
            if ( !allScanUpdates.isEmpty() )
            {
                ExecutorService executorService = Executors.newFixedThreadPool( allScanUpdates.size() );
                List<Future<?>> mergeFutures = new ArrayList<>();
                for ( BlockStorage<KEY,VALUE> scanUpdates : allScanUpdates )
                {
                    mergeFutures.add( executorService.submit( () ->
                    {
                        scanUpdates.doneAdding();
                        scanUpdates.merge( MERGE_FACTOR );
                        return null;
                    } ) );
                }
                executorService.shutdown();
                while ( !executorService.awaitTermination( 1, TimeUnit.SECONDS ) )
                {
                    // just wait longer
                }
                // Let potential exceptions in the merge threads have a chance to propagate
                for ( Future<?> mergeFuture : mergeFutures )
                {
                    mergeFuture.get();
                }
            }

            externalUpdates.doneAdding();
            // don't merge and sort the external updates

            // Build the tree from the scan updates
            phaseTracker.enterPhase( PhaseTracker.Phase.BUILD );
            writeScanUpdatesToTree();

            // Apply the external updates
            writeExternalUpdatesToTree();
            merged = true;
        }
        catch ( IOException e )
        {
            throw new UncheckedIOException( e );
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            throw new RuntimeException( "Got interrupted, so merge not completed", e );
        }
        catch ( ExecutionException e )
        {
            // Propagating merge exception from other thread
            Throwable executionException = e.getCause();
            if ( executionException instanceof RuntimeException )
            {
                throw (RuntimeException) executionException;
            }
            throw new RuntimeException( executionException );
        }
    }

    /**
     * We will loop over all external updates once to add them to the tree. This is done without checking any uniqueness.
     * If index is a uniqueness index we will then loop over external updates again and for each ADD or CHANGED update
     * we will verify that those entries are unique in the tree and throw as soon as we find a duplicate.
     * @throws IOException If something goes wrong while reading from index.
     * @throws IndexEntryConflictException If a duplicate is found.
     */
    private void writeExternalUpdatesToTree() throws IOException, IndexEntryConflictException
    {
        try ( Writer<KEY,VALUE> writer = tree.writer();
              IndexUpdateCursor<KEY,VALUE> updates = externalUpdates.reader() )
        {
            while ( updates.next() )
            {
                switch ( updates.updateMode() )
                {
                case ADDED:
                    writer.put( updates.key(), updates.value() );
                    break;
                case REMOVED:
                    writer.remove( updates.key() );
                    break;
                case CHANGED:
                    writer.remove( updates.key() );
                    writer.put( updates.key2(), updates.value() );
                    break;
                default:
                    throw new IllegalArgumentException( "Unknown update mode " + updates.updateMode() );
                }
            }
        }

        if ( descriptor.isUnique() )
        {
            verifyUniquenessOnExternalUpdates();
        }
    }

    /**
     * When this method is called, all updates have been applied to the tree. Here we loop over all updates again and for each update we verify that in the
     * end there are no duplicate entries for the value of the update update value in the tree. If intermediate duplicates was seen while applying the
     * updates, that is fine as long as the tree is completely unique now. Note that only updates that result in adding a new key to the tree can possible
     * cause a duplication to appear.
     * @throws IOException If something goes wrong while reading from index.
     * @throws IndexEntryConflictException If a duplicate is found.
     */
    private void verifyUniquenessOnExternalUpdates() throws IOException, IndexEntryConflictException
    {
        try ( IndexUpdateCursor<KEY,VALUE> updates = externalUpdates.reader() )
        {
            while ( updates.next() )
            {
                RawCursor<Hit<KEY,VALUE>,IOException> seek;
                switch ( updates.updateMode() )
                {
                case ADDED:
                    updates.key().setCompareId( false );
                    seek = tree.seek( updates.key(), updates.key() );
                    break;
                case CHANGED:
                    updates.key2().setCompareId( false );
                    seek = tree.seek( updates.key2(), updates.key2() );
                    break;
                case REMOVED:
                    // Can't cause uniqueness conflict
                    seek = null;
                    break;
                default:
                    throw new IllegalArgumentException( "Unknown update mode " + updates.updateMode() );
                }
                verifyUniqueSeek( seek );
            }
        }
    }

    private void verifyUniqueSeek( RawCursor<Hit<KEY,VALUE>,IOException> seek ) throws IOException, IndexEntryConflictException
    {
        if ( seek != null )
        {
            if ( seek.next() )
            {
                long firstEntityId = seek.get().key().getEntityId();
                if ( seek.next() )
                {
                    long secondEntityId = seek.get().key().getEntityId();
                    throw new IndexEntryConflictException( firstEntityId, secondEntityId, seek.get().key().asValues() );
                }
            }
        }
    }

    private void writeScanUpdatesToTree() throws IOException, IndexEntryConflictException
    {
        ConflictDetectingValueMerger<KEY,VALUE> conflictDetector = getMainConflictDetector();
        try ( MergingBlockEntryReader<KEY,VALUE> allEntries = new MergingBlockEntryReader<>( layout ) )
        {
            for ( BlockStorage<KEY,VALUE> scanUpdates : allScanUpdates )
            {
                try ( BlockReader<KEY,VALUE> reader = scanUpdates.reader() )
                {
                    BlockEntryReader<KEY,VALUE> singleMergedBlock = reader.nextBlock();
                    if ( singleMergedBlock != null )
                    {
                        allEntries.addSource( singleMergedBlock );
                        if ( reader.nextBlock() != null )
                        {
                            throw new IllegalStateException( "Final BlockStorage had multiple blocks" );
                        }
                    }
                }
            }

            int asMuchAsPossibleToTheLeft = 1;
            try ( Writer<KEY,VALUE> writer = tree.writer( asMuchAsPossibleToTheLeft ) )
            {
                while ( allEntries.next() )
                {
                    conflictDetector.controlConflictDetection( allEntries.key() );
                    writer.merge( allEntries.key(), allEntries.value(), conflictDetector );
                    if ( conflictDetector.wasConflicting() )
                    {
                        conflictDetector.reportConflict( allEntries.key().asValues() );
                    }
                }
            }
        }
    }

    @Override
    public IndexUpdater newPopulatingUpdater()
    {
        if ( merged )
        {
            // Will need the reader from newReader, which a sub-class of this class implements
            return super.newPopulatingUpdater();
        }

        return new IndexUpdater()
        {
            private volatile boolean closed;

            @Override
            public void process( IndexEntryUpdate<?> update )
            {
                assertOpen();
                try
                {
                    externalUpdates.add( update );
                }
                catch ( IOException e )
                {
                    throw new UncheckedIOException( e );
                }
            }

            @Override
            public void close()
            {
                closed = true;
            }

            private void assertOpen()
            {
                if ( closed )
                {
                    throw new IllegalStateException( "Updater has been closed" );
                }
            }
        };
    }

    @Override
    public synchronized void drop()
    {
        try
        {
            closeBlockStorage();
        }
        finally
        {
            super.drop();
        }
    }

    @Override
    public void close( boolean populationCompletedSuccessfully )
    {
        try
        {
            closeBlockStorage();
        }
        finally
        {
            super.close( populationCompletedSuccessfully );
        }
    }

    private void closeBlockStorage()
    {
        List<Closeable> toClose = new ArrayList<>( allScanUpdates );
        toClose.add( externalUpdates );
        IOUtils.closeAllUnchecked( toClose );
    }
}
