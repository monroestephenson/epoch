package dev.epoch.associationfixture;

import jakarta.persistence.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Entity
@Table(name = "java_association")
public class JavaAssociationEntity {
    @Id public long id;
    @NotNull @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "required_fk")
    public JavaAssociationEntity required;
    @Nullable @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "nullable_fk")
    public JavaAssociationEntity optional;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "unspecified_fk")
    public JavaAssociationEntity unspecified;
}
