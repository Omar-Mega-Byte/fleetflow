CREATE TABLE actor_roles (
    actor_id uuid NOT NULL,
    role varchar(20) NOT NULL,
    CONSTRAINT pk_actor_roles PRIMARY KEY (actor_id, role),
    CONSTRAINT fk_actor_roles_actor FOREIGN KEY (actor_id) REFERENCES actors(id) ON DELETE CASCADE,
    CONSTRAINT ck_actor_roles_role CHECK (role IN ('CUSTOMER', 'MERCHANT', 'DRIVER', 'SUPPORT', 'DISPATCHER', 'ADMIN'))
);

CREATE INDEX idx_actor_roles_role ON actor_roles (role, actor_id);
CREATE INDEX idx_orders_branch_created_id ON orders (merchant_branch_id, created_at DESC, id DESC);
