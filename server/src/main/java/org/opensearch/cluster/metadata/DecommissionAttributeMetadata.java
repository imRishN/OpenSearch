/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.metadata;

import org.opensearch.OpenSearchParseException;
import org.opensearch.Version;
import org.opensearch.cluster.AbstractNamedDiffable;
import org.opensearch.cluster.NamedDiff;
import org.opensearch.cluster.decommission.DecommissionAttribute;
import org.opensearch.cluster.metadata.Metadata.Custom;
import org.opensearch.common.Nullable;
import org.opensearch.common.Strings;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.xcontent.ToXContent;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Contains metadata about decommission attribute
 *
 * @opensearch.internal
 */
public class DecommissionAttributeMetadata extends AbstractNamedDiffable<Custom> implements Custom {

    public static final String TYPE = "decommissionedAttribute";

    private final DecommissionAttribute decommissionAttribute;
    private final String attributeType;

    /**
     * Constructs new decommission attribute metadata
     *
     * @param decommissionAttribute attribute details
     * @param attributeType attribute type
     */
    public DecommissionAttributeMetadata(DecommissionAttribute decommissionAttribute, String attributeType) {
        this.decommissionAttribute = decommissionAttribute;
        this.attributeType = attributeType;
    }

    /**
     * Returns the current decommissioned attribute
     *
     * @return decommissioned attributes
     */
    public DecommissionAttribute decommissionAttribute() {
        return this.decommissionAttribute;
    }

    /**
     * Returns the attribute type
     *
     * @return attribute type
     */
    public String attributeType() {
        return this.attributeType;
    }

    /**
     * Creates a new instance with a updated attribute values.
     *
     * @param metadata        current metadata
     * @param attributeValues new attribute values
     * @return new instance with updated attribute values
     */
    public DecommissionAttributeMetadata withUpdatedAttributeValues(
        DecommissionAttributeMetadata metadata,
        List<String> attributeValues
    ) {
        return new DecommissionAttributeMetadata(
            new DecommissionAttribute(metadata.decommissionAttribute, attributeValues),
            metadata.attributeType()
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DecommissionAttributeMetadata that = (DecommissionAttributeMetadata) o;

        if (!attributeType.equals(that.attributeType)) return false;
        return decommissionAttribute.equals(that.decommissionAttribute);
    }

    /**
     * Checks if this instance and the given instance share the same decommissioned attributeName
     * and only differ in the attributeValue {@link DecommissionAttribute#attributeValues()}
     *
     * @param other other decommission attribute metadata
     * @return {@code true} iff both instances contain the same attributeName
     */
    public boolean equalsIgnoreValues(@Nullable DecommissionAttributeMetadata other) {
        if (other == null) {
            return false;
        }
        if (!attributeType.equals(other.attributeType)) return false;
        return decommissionAttribute.equalsIgnoreValues(other.decommissionAttribute);
    }

    @Override
    public int hashCode() {
        return Objects.hash(attributeType, decommissionAttribute);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getWriteableName() {
        return TYPE;
    }

    @Override
    public Version getMinimalSupportedVersion() {
        return Version.CURRENT.minimumCompatibilityVersion();
    }

    public DecommissionAttributeMetadata(StreamInput in) throws IOException {
        this.attributeType = in.readString();
        this.decommissionAttribute = new DecommissionAttribute(in);
    }

    public static NamedDiff<Custom> readDiffFrom(StreamInput in) throws IOException {
        return readDiffFrom(Custom.class, TYPE, in);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeTo(StreamOutput out) throws IOException {
        decommissionAttribute.writeTo(out);
        out.writeString(attributeType);
    }

    public static DecommissionAttributeMetadata fromXContent(XContentParser parser) throws IOException {
        XContentParser.Token token;
        DecommissionAttribute decommissionAttribute = null;
        String attributeType = null;
        if ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                attributeType = parser.currentName();
                if (parser.nextToken() != XContentParser.Token.START_OBJECT) {
                    throw new OpenSearchParseException("failed to parse decommission attribute type [{}], expected object", attributeType);
                }
                token = parser.nextToken();
                if (token != XContentParser.Token.END_OBJECT) {
                    if (token == XContentParser.Token.FIELD_NAME) {
                        String fieldName = parser.currentName();
                        List<String> values = new ArrayList<>();
                        token = parser.nextToken();
                        if (token == XContentParser.Token.VALUE_STRING) {
                            values.add(parser.text());
                        } else if (token == XContentParser.Token.START_ARRAY) {
                            while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                                if (token == XContentParser.Token.VALUE_STRING) {
                                    values.add(parser.text());
                                } else {
                                    parser.skipChildren();
                                }
                            }
                        }
                        decommissionAttribute = new DecommissionAttribute(fieldName, values);
                    } else {
                        throw new OpenSearchParseException("failed to parse attribute type [{}], unexpected type", attributeType);
                    }
                } else {
                    throw new OpenSearchParseException("failed to parse attribute type [{}]", attributeType);
                }
            }
        }
        return new DecommissionAttributeMetadata(decommissionAttribute, attributeType);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public XContentBuilder toXContent(XContentBuilder builder, ToXContent.Params params) throws IOException {
        toXContent(decommissionAttribute, attributeType, builder, params);
        return builder;
    }

    @Override
    public EnumSet<Metadata.XContentContext> context() {
        return Metadata.API_AND_GATEWAY;
    }

    /**
     * @param decommissionAttribute decommission attribute
     * @param attributeType         attribute type
     * @param builder               XContent builder
     * @param params                serialization parameters
     */
    public static void toXContent(
        DecommissionAttribute decommissionAttribute,
        String attributeType,
        XContentBuilder builder,
        ToXContent.Params params
    ) throws IOException {
        builder.startObject(attributeType);
        builder.startArray(decommissionAttribute.attributeName());
        for (String value : decommissionAttribute.attributeValues()) {
            builder.value(value);
        }
        builder.endArray();
        builder.endObject();
    }

    @Override
    public String toString() {
        return Strings.toString(this);
    }
}
