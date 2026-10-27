package dev.epoch.hibernate7fixture

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.NamedQuery
import jakarta.persistence.OneToMany
import jakarta.persistence.Table

/** Compiled against Hibernate 7.4.9/JPA 3.2, without the adapter's bundled Hibernate 6 classes. */
@Entity
@Table(name = "hibernate7_team")
@NamedQuery(name = "GenericTeam.byMember", query = "select t from GenericTeam t join t.members m where m.name = :name")
open class GenericTeam {
    @field:Id open var id: Long = 0
    open var name: String = ""

    @field:ManyToMany
    @field:JoinTable(name = "hibernate7_team_members",
        joinColumns = [JoinColumn(name = "team_id")],
        inverseJoinColumns = [JoinColumn(name = "member_id")])
    open var members: List<GenericMember> = emptyList()

    @field:OneToMany
    @field:JoinTable(name = "hibernate7_team_tasks",
        joinColumns = [JoinColumn(name = "team_id")],
        inverseJoinColumns = [JoinColumn(name = "task_id")])
    open var tasks: List<GenericTask> = emptyList()
}

@Entity
@Table(name = "hibernate7_member")
open class GenericMember {
    @field:Id open var id: Long = 0
    open var name: String = ""
}

@Entity
@Table(name = "hibernate7_task")
open class GenericTask {
    @field:Id open var id: Long = 0
    open var description: String = ""
}
