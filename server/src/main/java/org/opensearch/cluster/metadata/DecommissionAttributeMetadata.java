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
import org.opensearch.cluster.decommission.DecommissionStatus;
import org.opensearch.cluster.metadata.Metadata.Custom;
import org.opensearch.common.Nullable;
import org.opensearch.common.Strings;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.xcontent.ToXContent;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentParser;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Objects;

/**
 * Contains metadata about decommission attribute
 *
 * @opensearch.internal
 */
public class DecommissionAttributeMetadata extends AbstractNamedDiffable<Custom> implements Custom {

    public static final String TYPE = "decommissionedAttribute";

    private final DecommissionAttribute decommissionAttribute;
    private final DecommissionStatus status;
    public static final String attributeType = "awareness";

    /**
     * Constructs new decommission attribute metadata with given status
     *
     * @param decommissionAttribute attribute details
     * @param status                current status of the attribute decommission
     */
    public DecommissionAttributeMetadata(DecommissionAttribute decommissionAttribute, DecommissionStatus status) {
        this.decommissionAttribute = decommissionAttribute;
        this.status = status;
    }

    /**
     * Constructs new decommission attribute metadata with status as {@link DecommissionStatus#INIT}
     *
     * @param decommissionAttribute attribute details
     */
    public DecommissionAttributeMetadata(DecommissionAttribute decommissionAttribute) {
        this(decommissionAttribute, DecommissionStatus.INIT);
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
     * Returns the current status of the attribute decommission
     *
     * @return attribute type
     */
    public DecommissionStatus status() {
        return this.status;
    }

    public DecommissionAttributeMetadata withUpdatedStatus(DecommissionAttributeMetadata metadata, DecommissionStatus status) {
        return new DecommissionAttributeMetadata(metadata.decommissionAttribute(), status);
    }

    /**
     * Creates a new instance with a updated attribute value.
     *
     * @param metadata       current metadata
     * @param attributeValue new attribute value
     * @return new instance with updated attribute value and status as DecommissionStatus.INIT
     */
    public DecommissionAttributeMetadata withUpdatedAttributeValue(DecommissionAttributeMetadata metadata, String attributeValue) {
        return new DecommissionAttributeMetadata(
            new DecommissionAttribute(metadata.decommissionAttribute, attributeValue),
            DecommissionStatus.INIT
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DecommissionAttributeMetadata that = (DecommissionAttributeMetadata) o;

        if (!status.equals(that.status)) return false;
        return decommissionAttribute.equals(that.decommissionAttribute);
    }

    @Override
    public int hashCode() {
        return Objects.hash(attributeType, decommissionAttribute, status);
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
        return Version.V_2_3_0;
    }

    public DecommissionAttributeMetadata(StreamInput in) throws IOException {
        this.decommissionAttribute = new DecommissionAttribute(in);
        this.status = DecommissionStatus.fromString(in.readString());
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
        out.writeString(status.status());
    }

    public static DecommissionAttributeMetadata fromXContent(XContentParser parser) throws IOException {
        XContentParser.Token token;
        DecommissionAttribute decommissionAttribute = null;
        DecommissionStatus status = null;
        if ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                String currentFieldName = parser.currentName();
                if (attributeType.equals(currentFieldName)) {
                    if (parser.nextToken() != XContentParser.Token.START_OBJECT) {
                        throw new OpenSearchParseException(
                            "failed to parse decommission attribute type [{}], expected object",
                            attributeType
                        );
                    }
                    token = parser.nextToken();
                    if (token != XContentParser.Token.END_OBJECT) {
                        if (token == XContentParser.Token.FIELD_NAME) {
                            String fieldName = parser.currentName();
                            String value;
                            token = parser.nextToken();
                            if (token == XContentParser.Token.VALUE_STRING) {
                                value = parser.text();
                            } else {
                                throw new OpenSearchParseException(
                                    "failed to parse attribute [{}], expected string for attribute value",
                                    fieldName
                                );
                            }
                            decommissionAttribute = new DecommissionAttribute(fieldName, value);
                        } else {
                            throw new OpenSearchParseException("failed to parse attribute type [{}], unexpected type", attributeType);
                        }
                    } else {
                        throw new OpenSearchParseException("failed to parse attribute type [{}]", attributeType);
                    }
                } else if ("status".equals(currentFieldName)) {
                    if (parser.nextToken() != XContentParser.Token.VALUE_STRING) {
                        throw new OpenSearchParseException(
                            "failed to parse status of decommissioning, expected string but found unknown type"
                        );
                    }
                    status = DecommissionStatus.fromString(parser.text());
                } else {
                    throw new OpenSearchParseException(
                        "unknown field found [{}], failed to parse the decommission attribute",
                        currentFieldName
                    );
                }
            }
        }
        return new DecommissionAttributeMetadata(decommissionAttribute, status);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public XContentBuilder toXContent(XContentBuilder builder, ToXContent.Params params) throws IOException {
        toXContent(decommissionAttribute, status, attributeType, builder, params);
        return builder;
    }

    @Override
    public EnumSet<Metadata.XContentContext> context() {
        return Metadata.API_AND_GATEWAY;
    }

    /**
     * @param decommissionAttribute decommission attribute
     * @param status                decommission  status
     * @param attributeType         attribute type
     * @param builder               XContent builder
     * @param params                serialization parameters
     */
    public static void toXContent(
        DecommissionAttribute decommissionAttribute,
        DecommissionStatus status,
        String attributeType,
        XContentBuilder builder,
        ToXContent.Params params
    ) throws IOException {
        builder.startObject(attributeType);
        builder.field(decommissionAttribute.attributeName(), decommissionAttribute.attributeValue());
        builder.endObject();
        builder.field("status", status.status());
    }

    @Override
    public String toString() {
        return Strings.toString(this);
    }
}
