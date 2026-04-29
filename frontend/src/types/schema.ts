export type FieldType = "STRING" | "DATE" | "DECIMAL" | "ENUM" | "ARRAY";

export interface FieldSchema {
  name: string;
  type: FieldType | string;
  required: boolean;
  enumValues: string[] | null;
  itemFields: FieldSchema[] | null;
}

export interface ProblemDetailFieldError {
  path: string;
  message: string;
}

export interface ProblemDetail {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  code: string;
  message: string;
  details?: ProblemDetailFieldError[];
}
