/*
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
package io.trino.parquet.reader;

import io.airlift.slice.Slice;
import io.trino.parquet.ParquetDataSource;
import org.apache.parquet.format.Util;
import org.apache.parquet.format.converter.ParquetMetadataConverter;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.ColumnPath;
import org.apache.parquet.internal.column.columnindex.ColumnIndex;
import org.apache.parquet.internal.column.columnindex.OffsetIndex;
import org.apache.parquet.internal.filter2.columnindex.ColumnIndexStore;
import org.apache.parquet.internal.hadoop.metadata.IndexReference;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static java.util.Collections.emptySet;

/**
 * Internal implementation of {@link ColumnIndexStore}.
 * Copy of org.apache.parquet.hadoop.ColumnIndexStoreImpl which is not accessible
 */
public class TrinoColumnIndexStore
        implements ColumnIndexStore
{
    private interface IndexStore
    {
        ColumnIndex getColumnIndex();

        OffsetIndex getOffsetIndex();
    }

    private class TrinoIndexStore
            implements IndexStore
    {
        private final ColumnChunkMetaData meta;
        private ColumnIndex columnIndex;
        private boolean columnIndexRead;
        private final OffsetIndex offsetIndex;

        TrinoIndexStore(ColumnChunkMetaData meta)
        {
            this.meta = meta;
            OffsetIndex offsetIndex;
            try {
                offsetIndex = readOffsetIndex(meta);
            }
            catch (IOException e) {
                // If the I/O issue still stands it will fail the reading later;
                // otherwise we fail the filtering only with a missing offset index.
                offsetIndex = null;
            }
            if (offsetIndex == null) {
                throw new MissingOffsetIndexException(meta.getPath());
            }
            this.offsetIndex = offsetIndex;
        }

        @Override
        public ColumnIndex getColumnIndex()
        {
            if (!columnIndexRead) {
                try {
                    columnIndex = readColumnIndex(meta);
                }
                catch (IOException e) {
                    // If the I/O issue still stands it will fail the reading later;
                    // otherwise we fail the filtering only with a missing column index.
                }
                columnIndexRead = true;
            }
            return columnIndex;
        }

        @Override
        public OffsetIndex getOffsetIndex()
        {
            return offsetIndex;
        }

        private OffsetIndex readOffsetIndex(ColumnChunkMetaData column) throws IOException
        {
            IndexReference index = column.getOffsetIndexReference();
            if (index == null) {
                return null;
            }
            Slice slice = dataSource.readFully(index.getOffset(), index.getLength());
            return ParquetMetadataConverter.fromParquetOffsetIndex(Util.readOffsetIndex(slice.getInput()));
        }

        private ColumnIndex readColumnIndex(ColumnChunkMetaData meta) throws IOException
        {
            IndexReference index = meta.getColumnIndexReference();
            if (index == null) {
                return null;
            }
            Slice slice = dataSource.readFully(index.getOffset(), index.getLength());
            return ParquetMetadataConverter.fromParquetColumnIndex(meta.getPrimitiveType(), Util.readColumnIndex(slice.getInput()));
        }
    }

    // Used for columns are not in this parquet file
    private static final TrinoColumnIndexStore.IndexStore MISSING_INDEX_STORE = new IndexStore()
    {
        @Override
        public ColumnIndex getColumnIndex()
        {
            return null;
        }

        @Override
        public OffsetIndex getOffsetIndex()
        {
            return null;
        }
    };

    private static final TrinoColumnIndexStore EMPTY = new TrinoColumnIndexStore(null, new BlockMetaData(), emptySet())
    {
        @Override
        public ColumnIndex getColumnIndex(ColumnPath column)
        {
            return null;
        }

        @Override
        public OffsetIndex getOffsetIndex(ColumnPath column)
        {
            throw new MissingOffsetIndexException(column);
        }
    };

    private final ParquetDataSource dataSource;
    private final Map<ColumnPath, TrinoColumnIndexStore.IndexStore> store;

    /*
     * Creates a column index store which lazily reads column/offset indexes for the columns in paths. (paths are the set
     * of columns used for the projection)
     */
    public static ColumnIndexStore create(ParquetDataSource dataSource, BlockMetaData block, Set<ColumnPath> paths)
    {
        try {
            return new TrinoColumnIndexStore(dataSource, block, paths);
        }
        catch (MissingOffsetIndexException e) {
            return EMPTY;
        }
    }

    private TrinoColumnIndexStore(ParquetDataSource dataSource, BlockMetaData block, Set<ColumnPath> paths)
    {
        this.dataSource = dataSource;
        Map<ColumnPath, TrinoColumnIndexStore.IndexStore> store = new HashMap<>();
        for (ColumnChunkMetaData column : block.getColumns()) {
            ColumnPath path = column.getPath();
            if (paths.contains(path)) {
                store.put(path, new TrinoIndexStore(column));
            }
        }
        this.store = store;
    }

    @Override
    public ColumnIndex getColumnIndex(ColumnPath column)
    {
        return store.getOrDefault(column, MISSING_INDEX_STORE).getColumnIndex();
    }

    @Override
    public OffsetIndex getOffsetIndex(ColumnPath column)
    {
        return store.getOrDefault(column, MISSING_INDEX_STORE).getOffsetIndex();
    }
}
