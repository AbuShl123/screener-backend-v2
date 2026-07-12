-- Bank receipt link for a paid order. Multicard returns receipt_url on a successful payment — both on
-- the success callback and on the durable GET /payment/{uuid} used by the reconciliation sweep. It is
-- captured once at grant time and stored here so the order-history endpoints can surface it without a
-- live provider call per read. Nullable: it exists only for PAID orders, and Multicard marks it nullable
-- even on success.
ALTER TABLE orders ADD COLUMN receipt_url TEXT;
