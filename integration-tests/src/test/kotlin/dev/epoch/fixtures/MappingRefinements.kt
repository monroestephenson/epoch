package dev.epoch.fixtures

import jakarta.persistence.*

@Entity(name = "JoinedPerson")
@Table(name = "people")
@Inheritance(strategy = InheritanceType.JOINED)
open class JoinedPerson {
    @field:Id open var id: Long = 0
    @field:Column(name = "display_name") open var displayName: String? = null
    @field:Column(name = "created_at") open var createdAt: java.time.LocalDateTime? = null
}

@Entity(name = "JoinedEmployee")
@Table(name = "employees")
open class JoinedEmployee : JoinedPerson() {
    @field:Column(name = "department") open var department: String? = null
}

@Entity(name = "SecondaryAccount")
@Table(name = "accounts")
@SecondaryTable(name = "account_profiles", pkJoinColumns = [PrimaryKeyJoinColumn(name = "account_id")])
open class SecondaryAccount {
    @field:Id open var id: Long = 0
    @field:Column(name = "display_name") open var displayName: String? = null
    @field:Column(name = "biography", table = "account_profiles") open var biography: String? = null
}

@Entity(name = "TaggedAccount")
@Table(name = "accounts")
open class TaggedAccount {
    @field:Id open var id: Long = 0
    @field:ElementCollection
    @field:CollectionTable(name = "account_tags", joinColumns = [JoinColumn(name = "account_id")])
    @field:Column(name = "tag", nullable = false)
    open var tags: MutableSet<String> = mutableSetOf()
}

@Entity(name = "Team")
@Table(name = "teams")
open class Team {
    @field:Id open var id: Long = 0
    @field:ManyToMany
    @field:JoinTable(name = "team_members", joinColumns = [JoinColumn(name = "team_id")], inverseJoinColumns = [JoinColumn(name = "member_id")])
    open var members: MutableSet<Member> = mutableSetOf()
}

@Entity(name = "Member")
@Table(name = "members")
open class Member {
    @field:Id open var id: Long = 0
}

@Entity(name = "IdentityAccount")
@Table(name = "accounts")
open class IdentityAccount {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null
    @field:Column(name = "label") open var label: String? = null
}
