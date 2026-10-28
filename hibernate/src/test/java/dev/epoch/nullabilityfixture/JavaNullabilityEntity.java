package dev.epoch.nullabilityfixture;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Entity
@Table(name = "java_nullability")
public class JavaNullabilityEntity {
    @Id public long id;
    @NotNull @Column(name = "required_text") public String required = "";
    @Nullable @Column(name = "optional_text") public String optional;
    @Column(name = "unspecified_text") public String unspecified;
}
