import type { FieldSchema } from "../types/schema";
import { formatDisplay } from "../util/formatters";

interface ReadOnlyArrayTableProps {
  name: string;
  itemFields: FieldSchema[];
  rows: ReadonlyArray<Record<string, unknown>>;
}

const NUMERIC_TYPES = new Set(["DECIMAL", "INTEGER", "NUMBER"]);

function isNumericField(field: FieldSchema): boolean {
  return NUMERIC_TYPES.has(field.type.toUpperCase());
}

const HEADER_CELL =
  "border-b border-neutral-200 bg-table-head px-2 py-1.5 text-left text-10 font-semibold uppercase tracking-[0.3px] text-neutral-500";

const HEADER_CELL_NUMERIC = `${HEADER_CELL} text-right`;

const BODY_CELL = "px-2 py-1.5 text-12 text-brand-navy align-middle";
const BODY_CELL_NUMERIC = `${BODY_CELL} text-right tabular-nums`;

export function ReadOnlyArrayTable({ name, itemFields, rows }: ReadOnlyArrayTableProps) {
  return (
    <table
      data-testid="readonly-array"
      data-field-name={name}
      className="mt-1 w-full border-collapse text-12"
    >
      <thead>
        <tr>
          {itemFields.map((field) => (
            <th
              key={field.name}
              scope="col"
              className={isNumericField(field) ? HEADER_CELL_NUMERIC : HEADER_CELL}
            >
              {field.name}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.length === 0 ? (
          <tr data-testid="readonly-array-empty">
            <td
              colSpan={itemFields.length}
              className="px-3 py-4 text-center text-12 text-neutral-400"
            >
              No rows
            </td>
          </tr>
        ) : (
          rows.map((row, rowIndex) => (
            <tr
              key={rowIndex}
              data-testid="readonly-array-row"
              className="[&>td]:border-b [&>td]:border-neutral-100 last:[&>td]:border-b-0"
            >
              {itemFields.map((field) => (
                <td
                  key={field.name}
                  data-field={field.name}
                  className={isNumericField(field) ? BODY_CELL_NUMERIC : BODY_CELL}
                >
                  {formatDisplay(row[field.name])}
                </td>
              ))}
            </tr>
          ))
        )}
      </tbody>
    </table>
  );
}
