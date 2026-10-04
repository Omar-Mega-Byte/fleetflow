CREATE TABLE actors (
    id uuid CONSTRAINT pk_actors PRIMARY KEY,
    issuer varchar(255) NOT NULL,
    subject varchar(255) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uk_actors_issuer_subject UNIQUE (issuer, subject),
    CONSTRAINT ck_actors_issuer CHECK (length(btrim(issuer)) > 0),
    CONSTRAINT ck_actors_subject CHECK (length(btrim(subject)) > 0)
);

CREATE TABLE customers (
    id uuid CONSTRAINT pk_customers PRIMARY KEY,
    actor_id uuid NOT NULL,
    display_name varchar(120) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_customers_actor FOREIGN KEY (actor_id) REFERENCES actors(id),
    CONSTRAINT uk_customers_actor UNIQUE (actor_id),
    CONSTRAINT ck_customers_name CHECK (length(btrim(display_name)) > 0)
);

CREATE TABLE merchants (
    id uuid CONSTRAINT pk_merchants PRIMARY KEY,
    name varchar(120) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_merchants_name CHECK (length(btrim(name)) > 0)
);

CREATE TABLE merchant_branches (
    id uuid CONSTRAINT pk_merchant_branches PRIMARY KEY,
    merchant_id uuid NOT NULL,
    name varchar(120) NOT NULL,
    pickup_line1 varchar(200) NOT NULL,
    pickup_city varchar(120) NOT NULL,
    pickup_latitude numeric(9,6) NOT NULL,
    pickup_longitude numeric(9,6) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_merchant_branches_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id),
    CONSTRAINT ck_merchant_branches_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_merchant_branches_line1 CHECK (length(btrim(pickup_line1)) > 0),
    CONSTRAINT ck_merchant_branches_city CHECK (length(btrim(pickup_city)) > 0),
    CONSTRAINT ck_merchant_branches_latitude CHECK (pickup_latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_merchant_branches_longitude CHECK (pickup_longitude BETWEEN -180 AND 180)
);

CREATE TABLE merchant_staff_memberships (
    id uuid CONSTRAINT pk_merchant_staff_memberships PRIMARY KEY,
    actor_id uuid NOT NULL,
    branch_id uuid NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_merchant_staff_actor FOREIGN KEY (actor_id) REFERENCES actors(id),
    CONSTRAINT fk_merchant_staff_branch FOREIGN KEY (branch_id) REFERENCES merchant_branches(id),
    CONSTRAINT uk_merchant_staff_actor_branch UNIQUE (actor_id, branch_id)
);

CREATE TABLE drivers (
    id uuid CONSTRAINT pk_drivers PRIMARY KEY,
    actor_id uuid NOT NULL,
    active boolean NOT NULL DEFAULT true,
    suspended boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_drivers_actor FOREIGN KEY (actor_id) REFERENCES actors(id),
    CONSTRAINT uk_drivers_actor UNIQUE (actor_id)
);

CREATE TABLE vehicles (
    id uuid CONSTRAINT pk_vehicles PRIMARY KEY,
    driver_id uuid NOT NULL,
    type varchar(32) NOT NULL,
    capacity integer NOT NULL,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_vehicles_driver FOREIGN KEY (driver_id) REFERENCES drivers(id),
    CONSTRAINT uk_vehicles_driver UNIQUE (driver_id),
    CONSTRAINT ck_vehicles_type CHECK (length(btrim(type)) > 0),
    CONSTRAINT ck_vehicles_capacity CHECK (capacity > 0)
);

CREATE TABLE orders (
    id uuid CONSTRAINT pk_orders PRIMARY KEY,
    customer_id uuid NOT NULL,
    merchant_branch_id uuid NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'CREATED',
    customer_name_snapshot varchar(120) NOT NULL,
    merchant_name_snapshot varchar(120) NOT NULL,
    branch_name_snapshot varchar(120) NOT NULL,
    pickup_line1 varchar(200) NOT NULL,
    pickup_city varchar(120) NOT NULL,
    pickup_latitude numeric(9,6) NOT NULL,
    pickup_longitude numeric(9,6) NOT NULL,
    delivery_line1 varchar(200) NOT NULL,
    delivery_city varchar(120) NOT NULL,
    delivery_latitude numeric(9,6) NOT NULL,
    delivery_longitude numeric(9,6) NOT NULL,
    currency varchar(3) NOT NULL,
    total_amount numeric(18,2) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_orders_branch FOREIGN KEY (merchant_branch_id) REFERENCES merchant_branches(id),
    CONSTRAINT ck_orders_status CHECK (status = 'CREATED'),
    CONSTRAINT ck_orders_pickup_latitude CHECK (pickup_latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_orders_pickup_longitude CHECK (pickup_longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_orders_delivery_latitude CHECK (delivery_latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_orders_delivery_longitude CHECK (delivery_longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_orders_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_orders_total CHECK (total_amount >= 0)
);

CREATE TABLE order_items (
    id uuid CONSTRAINT pk_order_items PRIMARY KEY,
    order_id uuid NOT NULL,
    line_number integer NOT NULL,
    description_snapshot varchar(200) NOT NULL,
    quantity integer NOT NULL,
    unit_amount numeric(18,2) NOT NULL,
    line_amount numeric(18,2) NOT NULL,
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT uk_order_items_order_line UNIQUE (order_id, line_number),
    CONSTRAINT ck_order_items_line CHECK (line_number > 0),
    CONSTRAINT ck_order_items_description CHECK (length(btrim(description_snapshot)) > 0),
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_items_unit CHECK (unit_amount >= 0),
    CONSTRAINT ck_order_items_amount CHECK (line_amount = unit_amount * quantity)
);

CREATE INDEX idx_orders_customer_created_id ON orders (customer_id, created_at DESC, id DESC);
