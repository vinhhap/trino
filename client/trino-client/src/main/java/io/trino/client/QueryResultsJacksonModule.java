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
package io.trino.client;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.google.common.collect.ImmutableList;
import io.trino.client.JsonDecodingUtils.TypeDecoder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static io.trino.client.JsonDecodingUtils.createTypeDecoders;
import static java.util.Collections.unmodifiableList;

/**
 * Decodes inline JSON rows directly to typed values while the {@link QueryResults} response
 * is parsed, instead of buffering them and decoding again later. The columns are captured
 * into a per-call attribute when the "columns" field is deserialized, and used to decode
 * "data" when it follows (which is how the server serializes it). When "columns" does not
 * precede "data", or for spooling protocol responses, the {@link QueryDataJacksonModule}
 * behavior is kept.
 * <p>
 * This module is used only by {@link StatementClientV1}. The generic {@link QueryDataJacksonModule}
 * behavior of buffering "data" as-is, which allows faithful re-serialization, remains the default
 * for other users of the codec.
 */
class QueryResultsJacksonModule
        extends SimpleModule
{
    private static final String COLUMNS_ATTRIBUTE = "io.trino.client.columns";

    public QueryResultsJacksonModule(boolean supportsVariantBinary)
    {
        super(QueryResultsJacksonModule.class.getSimpleName(), Version.unknownVersion());
        addDeserializer(QueryData.class, new EagerQueryDataDeserializer(supportsVariantBinary));
        setDeserializerModifier(new BeanDeserializerModifier()
        {
            @Override
            public JsonDeserializer<?> modifyCollectionDeserializer(DeserializationConfig config, CollectionType type, BeanDescription beanDescription, JsonDeserializer<?> deserializer)
            {
                if (type.getContentType().getRawClass() == Column.class) {
                    return new ColumnsCapturingDeserializer(deserializer);
                }
                return deserializer;
            }
        });
    }

    private static class ColumnsCapturingDeserializer
            extends DelegatingDeserializer
    {
        public ColumnsCapturingDeserializer(JsonDeserializer<?> delegate)
        {
            super(delegate);
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> newDelegatee)
        {
            return new ColumnsCapturingDeserializer(newDelegatee);
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context)
                throws IOException
        {
            Object columns = super.deserialize(parser, context);
            context.setAttribute(COLUMNS_ATTRIBUTE, columns);
            return columns;
        }
    }

    private static class EagerQueryDataDeserializer
            extends QueryDataJacksonModule.Deserializer
    {
        private final boolean supportsVariantBinary;

        public EagerQueryDataDeserializer(boolean supportsVariantBinary)
        {
            this.supportsVariantBinary = supportsVariantBinary;
        }

        @Override
        public QueryData deserialize(JsonParser parser, DeserializationContext context)
                throws IOException
        {
            if (parser.currentToken() == JsonToken.START_ARRAY) {
                @SuppressWarnings("unchecked")
                List<Column> columns = (List<Column>) context.getAttribute(COLUMNS_ATTRIBUTE);
                if (columns != null && !columns.isEmpty()) {
                    return decodeRows(parser, context, columns);
                }
            }
            return super.deserialize(parser, context);
        }

        private QueryData decodeRows(JsonParser parser, DeserializationContext context, List<Column> columns)
                throws IOException
        {
            TypeDecoder[] decoders = createTypeDecoders(columns, supportsVariantBinary);
            ImmutableList.Builder<List<Object>> rows = ImmutableList.builder();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() != JsonToken.START_ARRAY) {
                    return (QueryData) context.handleUnexpectedToken(QueryData.class, parser);
                }
                List<Object> row = new ArrayList<>(decoders.length);
                for (TypeDecoder decoder : decoders) {
                    if (parser.nextToken() == JsonToken.VALUE_NULL) {
                        row.add(null);
                    }
                    else {
                        row.add(decoder.decode(parser));
                    }
                }
                if (parser.nextToken() != JsonToken.END_ARRAY) {
                    return (QueryData) context.handleUnexpectedToken(QueryData.class, parser);
                }
                rows.add(unmodifiableList(row));
            }
            return TypedQueryData.of(rows.build());
        }
    }
}
